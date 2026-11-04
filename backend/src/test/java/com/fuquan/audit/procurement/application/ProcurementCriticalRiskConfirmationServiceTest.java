package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementReviewReportRepository;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProcurementCriticalRiskConfirmationServiceTest {

    @Test
    void criticalReportConfirmationPersistsActorTimeAndServerOwnedContent() {
        Fixture fixture = fixture("CRITICAL");

        ProcurementCriticalRiskConfirmationService.Confirmation confirmation = fixture.service.confirm(
                fixture.project.id(), fixture.report.id(), "operation-1", "已复核风险明细", fixture.user);

        assertThat(confirmation.projectId()).isEqualTo(fixture.project.id());
        assertThat(confirmation.reportId()).isEqualTo(fixture.report.id());
        assertThat(confirmation.confirmerUserId()).isEqualTo(fixture.user.userId());
        assertThat(confirmation.confirmerName()).isEqualTo(fixture.user.displayName());
        assertThat(confirmation.confirmedTime()).isNotNull();
        assertThat(confirmation.confirmedContent()).isEqualTo(fixture.report.overallConclusion());
        assertThat(confirmation.confirmationNote()).isEqualTo("已复核风险明细");
        assertThat(fixture.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(ProcurementCriticalRiskConfirmationService.ACTION);
            assertThat(event.resourceType()).isEqualTo(
                    ProcurementCriticalRiskConfirmationService.RESOURCE_TYPE);
            assertThat(event.resourceId()).isEqualTo(fixture.report.id().toString());
            assertThat(event.afterData()).contains(
                    fixture.report.overallConclusion(), fixture.user.displayName(), "operation-1");
        });
        assertThat(fixture.service.find(fixture.project.id(), fixture.report.id()))
                .contains(confirmation);
    }

    @Test
    void concurrentRetriesPersistOnlyOneConfirmationFact() {
        Fixture fixture = fixture("CRITICAL");

        List<ProcurementCriticalRiskConfirmationService.Confirmation> confirmations =
                IntStream.range(0, 12).parallel()
                        .mapToObj(index -> fixture.service.confirm(
                                fixture.project.id(), fixture.report.id(), "operation-" + index,
                                null, fixture.user))
                        .toList();

        assertThat(fixture.events).hasSize(1);
        assertThat(confirmations).allMatch(value -> value.reportId().equals(fixture.report.id()));
        assertThat(confirmations.stream().map(
                ProcurementCriticalRiskConfirmationService.Confirmation::confirmedTime).distinct())
                .hasSize(1);
        assertThat(fixture.idempotency.size()).isEqualTo(1);
    }

    @Test
    void competingServiceInstancesCannotPersistTwoConfirmationFacts() {
        Fixture fixture = fixture("CRITICAL");

        List<Object> outcomes = IntStream.range(0, 24).parallel()
                .mapToObj(index -> {
                    ProcurementCriticalRiskConfirmationService service = index % 2 == 0
                            ? fixture.service
                            : fixture.secondService;
                    try {
                        return service.confirm(
                                fixture.project.id(), fixture.report.id(), "operation-" + index,
                                null, fixture.user);
                    } catch (BusinessException conflict) {
                        return conflict;
                    }
                })
                .toList();

        assertThat(fixture.events).hasSize(1);
        assertThat(fixture.idempotency.size()).isEqualTo(1);
        assertThat(outcomes).anyMatch(
                ProcurementCriticalRiskConfirmationService.Confirmation.class::isInstance);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast))
                .allMatch(exception -> exception.errorCode() == BusinessErrorCode.CONFLICT);
    }

    @Test
    void nonCriticalAndStaleReportsCannotBeConfirmed() {
        Fixture fixture = fixture("INFO");

        assertThatThrownBy(() -> fixture.service.confirm(
                fixture.project.id(), fixture.report.id(), "operation-1", null, fixture.user))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                BusinessErrorCode.BUSINESS_VALIDATION_FAILED));
        assertThatThrownBy(() -> fixture.service.confirm(
                fixture.project.id(), UUID.randomUUID(), "operation-2", null, fixture.user))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(fixture.events).isEmpty();
    }

    private static Fixture fixture(String riskLevel) {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(
                projectId, "BA-TEST", "严重风险确认测试项目", "M1001", "办公设备",
                new BigDecimal("100000.00"), null, "SUBMITTED", Instant.now(),
                "采购部", "货物", "公开招标", null, "张三", null);
        ProcurementReviewReport report = new ProcurementReviewReport(
                UUID.randomUUID(), projectId, "PCR-TEST", riskLevel, Instant.now(),
                "本项目存在 1 项严重风险，建议暂停后续采购流程。", "{}");
        List<OperationAuditEvent> events = new CopyOnWriteArrayList<>();
        OperationAuditPort auditPort = events::add;
        OperationAuditQueryPort auditQuery = (userId, action, resourceType, from, to) -> events.stream()
                .filter(event -> action == null || action.equals(event.action()))
                .filter(event -> resourceType == null || resourceType.equals(event.resourceType()))
                .toList();
        ProcurementProjectQueryService projectQuery = new ProcurementProjectQueryService(
                new StubProjectPort(project));
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
        ProcurementCriticalRiskConfirmationService service =
                new ProcurementCriticalRiskConfirmationService(
                        new StubReportRepository(report),
                        projectQuery,
                        idempotency,
                        auditPort,
                        auditQuery,
                        new ObjectMapper(),
                        clock);
        ProcurementCriticalRiskConfirmationService secondService =
                new ProcurementCriticalRiskConfirmationService(
                        new StubReportRepository(report),
                        projectQuery,
                        idempotency,
                        auditPort,
                        auditQuery,
                        new ObjectMapper(),
                        clock);
        CurrentUser user = new CurrentUser(
                "auditor-1", "auditor", "审查员李明", Set.of("AUDITOR"));
        return new Fixture(project, report, user, events, idempotency, service, secondService);
    }

    private record Fixture(
            ProcurementProject project,
            ProcurementReviewReport report,
            CurrentUser user,
            List<OperationAuditEvent> events,
            InMemoryIdempotencyAdapter idempotency,
            ProcurementCriticalRiskConfirmationService service,
            ProcurementCriticalRiskConfirmationService secondService) {}

    private record StubProjectPort(ProcurementProject project) implements ProcurementProjectQueryPort {
        @Override
        public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(List.of(project), 1);
        }

        @Override
        public Optional<ProcurementProject> findById(UUID id) {
            return project.id().equals(id) ? Optional.of(project) : Optional.empty();
        }
    }

    private record StubReportRepository(
            ProcurementReviewReport report) implements ProcurementReviewReportRepository {
        @Override
        public void save(ProcurementReviewReport value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProcurementReviewReport> findLatestByProjectId(UUID projectId) {
            return report.projectId().equals(projectId) ? Optional.of(report) : Optional.empty();
        }
    }
}
