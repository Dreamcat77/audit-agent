package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewSnapshotRepository;
import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementFieldConfirmationServiceTest {

    @Test
    void confirmationStoresImmutableFieldEvidenceInAuditEvent() {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(projectId, "BA-TEST", "1", "M1001", "办公设备",
                new BigDecimal("100.00"), null, "DRAFT", Instant.now(), "采购部", "货物", "公开招标", null, "张三", null);
        List<OperationAuditEvent> events = new ArrayList<>();
        OperationAuditPort auditPort = events::add;
        OperationAuditQueryPort auditQuery = (userId, action, resourceType, from, to) -> events.stream()
                .filter(event -> action == null || action.equals(event.action()))
                .toList();
        ProcurementProjectQueryService projectQuery = new ProcurementProjectQueryService(new StubPort(project));
        ProcurementAgentReviewService review = new ProcurementAgentReviewService(projectQuery);
        ProcurementAgentReviewSnapshotService snapshots = new ProcurementAgentReviewSnapshotService(
                projectQuery, review, new StubSnapshotRepository(), new ObjectMapper().registerModule(new JavaTimeModule()));
        ProcurementFieldConfirmationService service = new ProcurementFieldConfirmationService(
                snapshots, projectQuery, new OperationAuditService(auditPort, new ObjectMapper()), auditQuery,
                new ObjectMapper());
        CurrentUser user = new CurrentUser("user-1", "applicant", "申请人张三", Set.of("PROCUREMENT_APPLICANT"));

        ProcurementFieldConfirmationService.Confirmation confirmation = service.confirm(
                projectId, "PROJECT_NAME", "确认项目名称真实有效", user);

        assertThat(confirmation.fieldCode()).isEqualTo("PROJECT_NAME");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(ProcurementFieldConfirmationService.ACTION);
            assertThat(event.resourceId()).isEqualTo(projectId.toString());
            assertThat(event.confirmationFieldCode()).isEqualTo("PROJECT_NAME");
            assertThat(event.confirmationFieldName()).isEqualTo("项目名称合理性");
            assertThat(event.confirmationFieldValue()).isEqualTo("1");
            assertThat(event.confirmationRulePrompt()).isNotBlank();
            assertThat(event.confirmationNote()).isEqualTo("确认项目名称真实有效");
            assertThat(event.afterData()).contains("fieldCode", "confirmationNote");
        });
        assertThat(service.find(projectId)).singleElement().satisfies(saved -> {
            assertThat(saved.confirmerUserId()).isEqualTo("user-1");
            assertThat(saved.confirmationNote()).isEqualTo("确认项目名称真实有效");
        });
    }

    private record StubPort(ProcurementProject project) implements ProcurementProjectQueryPort {
        @Override
        public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(List.of(project), 1);
        }

        @Override
        public Optional<ProcurementProject> findById(UUID id) {
            return project.id().equals(id) ? Optional.of(project) : Optional.empty();
        }
    }

    private static final class StubSnapshotRepository implements ProcurementAgentReviewSnapshotRepository {
        private ProcurementAgentReviewSnapshot value;

        @Override
        public void save(ProcurementAgentReviewSnapshot snapshot) { value = snapshot; }

        @Override
        public Optional<ProcurementAgentReviewSnapshot> findByProjectId(UUID projectId) {
            return value != null && value.projectId().equals(projectId) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public void deleteByProjectId(UUID projectId) { value = null; }
    }
}
