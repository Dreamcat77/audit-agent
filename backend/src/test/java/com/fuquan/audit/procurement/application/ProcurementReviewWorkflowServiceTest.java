package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementReviewWorkflowServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");

    @Test
    void tenderReviewCompletionAdvancesToReportWithoutRequiringAuxiliaryAnalyses() {
        UUID projectId = UUID.randomUUID();
        ProcurementReviewWorkflowService service = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new SingleProjectQueryPort(project(projectId))),
                new MemoryWorkflowRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

        service.start(projectId);
        ProcurementReviewWorkflowService.State completed = service.recordTenderReview(projectId,
                "已完成招标文件规则扫描和 AI 语义分析，共 7 条风险");

        ProcurementReviewStep tenderStep = completed.steps().stream()
                .filter(step -> ProcurementReviewWorkflowService.TENDER_DOCUMENT.equals(step.stepCode()))
                .findFirst().orElseThrow();
        ProcurementReviewStep collusionStep = completed.steps().stream()
                .filter(step -> ProcurementReviewWorkflowService.COLLUSION.equals(step.stepCode()))
                .findFirst().orElseThrow();

        assertThat(tenderStep.status()).isEqualTo(ProcurementReviewStep.COMPLETED);
        assertThat(tenderStep.completedTime()).isEqualTo(NOW);
        assertThat(tenderStep.resultSummary()).contains("共 7 条风险");
        assertThat(completed.workflow().currentStepOrder()).isEqualTo(7);
        assertThat(collusionStep.status()).isEqualTo(ProcurementReviewStep.PENDING);
        assertThat(completed.steps().stream().filter(s -> s.stepOrder() < 5))
                .allSatisfy(s -> assertThat(s.status()).isEqualTo(ProcurementReviewStep.PENDING));
        service.recordReportGenerated(projectId, "报告完成");
        service.ensureReportCompleted(projectId);
    }

    @Test
    void rerunningBlacklistCheckDoesNotRegressCompletedTenderReview() {
        UUID projectId = UUID.randomUUID();
        ProcurementReviewWorkflowService service = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new SingleProjectQueryPort(project(projectId))),
                new MemoryWorkflowRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

        service.start(projectId);
        service.recordQuoteCheck(projectId, "询比价分析完成");
        service.recordBlacklistCheck(projectId, "黑名单比对完成");
        service.recordTenderReview(projectId, "招标文件审查完成");
        ProcurementReviewWorkflowService.State afterRerun = service.recordBlacklistCheck(projectId,
                "黑名单比对重新执行完成");

        ProcurementReviewStep tenderStep = afterRerun.steps().stream()
                .filter(step -> ProcurementReviewWorkflowService.TENDER_DOCUMENT.equals(step.stepCode()))
                .findFirst().orElseThrow();
        assertThat(tenderStep.status()).isEqualTo(ProcurementReviewStep.COMPLETED);
        assertThat(tenderStep.completedTime()).isEqualTo(NOW);
        assertThat(tenderStep.resultSummary()).isEqualTo("招标文件审查完成");
        assertThat(afterRerun.workflow().currentStepOrder()).isEqualTo(7);
    }

    @Test
    void optionalWorkflowHooksAreNoOpBeforeWizardStarts() {
        UUID projectId = UUID.randomUUID();
        MemoryWorkflowRepository repository = new MemoryWorkflowRepository();
        ProcurementReviewWorkflowService service = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new SingleProjectQueryPort(project(projectId))),
                repository, Clock.fixed(NOW, ZoneOffset.UTC));

        service.recordReportGeneratedIfStarted(projectId, "直接生成报告");
        service.ensureReportCompletedIfStarted(projectId);
        service.recordApprovalStartedIfStarted(projectId, "直接提交审批");

        assertThat(repository.findByProjectId(projectId)).isEmpty();
    }

    @Test
    void optionalApprovalHookStillRejectsIncompleteExplicitWorkflow() {
        UUID projectId = UUID.randomUUID();
        ProcurementReviewWorkflowService service = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new SingleProjectQueryPort(project(projectId))),
                new MemoryWorkflowRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        service.start(projectId);

        assertThatThrownBy(() -> service.ensureReportCompletedIfStarted(projectId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能提交审批");
        assertThatThrownBy(() -> service.recordApprovalStartedIfStarted(projectId, "提交审批"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先完成");
    }

    @Test
    void quotationOnlyCanGenerateReportWithoutInventingTenderOrBlacklistFacts() {
        UUID id = UUID.randomUUID();
        var service = new ProcurementReviewWorkflowService(new ProcurementProjectQueryService(new SingleProjectQueryPort(project(id))),
                new MemoryWorkflowRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(service.start(id).workflow().currentStepOrder()).isEqualTo(5);
        assertThatThrownBy(() -> service.recordReportGenerated(id, "未分析材料"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("材料分析");
        var state = service.recordQuoteCheck(id, "比价完成");
        assertThat(state.workflow().currentStepOrder()).isEqualTo(7);
        assertThat(state.steps().stream().filter(s -> List.of("BLACKLIST", "TENDER_DOCUMENT", "COLLUSION").contains(s.stepCode())))
                .allSatisfy(s -> assertThat(s.status()).isNotEqualTo(ProcurementReviewStep.COMPLETED));
        service.recordReportGenerated(id, "报告完成");
        var completed = service.recordApprovalStarted(id, "审批已提交");
        assertThat(completed.workflow().status()).isEqualTo(ProcurementReviewWorkflow.COMPLETED);
        assertThat(service.require(id).workflow().status()).isEqualTo(ProcurementReviewWorkflow.COMPLETED);
    }

    @Test
    void legacyWorkflowReopensAtMaterialsWithoutUpdatingOrMarkingOptionalStepsComplete() {
        UUID id = UUID.randomUUID();
        var repository = new MemoryWorkflowRepository();
        var service = new ProcurementReviewWorkflowService(new ProcurementProjectQueryService(new SingleProjectQueryPort(project(id))),
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
        var created = service.start(id);
        var old = created.workflow();
        repository.update(new ProcurementReviewWorkflow(old.id(), id, 1, old.status(), NOW, NOW), created.steps());
        assertThat(service.require(id).workflow().currentStepOrder()).isEqualTo(5);
        assertThat(repository.snapshot.workflow().currentStepOrder()).isEqualTo(1);
        assertThat(service.require(id).steps().get(0).status()).isEqualTo(ProcurementReviewStep.PENDING);
    }

    @Test
    void verifiedNonQuoteReportReopensWithoutInventingQuoteOrTenderCompletion() {
        UUID id = UUID.randomUUID();
        var service = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new SingleProjectQueryPort(project(id))),
                new MemoryWorkflowRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        service.start(id);
        assertThatThrownBy(() -> service.recordReportGenerated(id, "未核对材料"))
                .isInstanceOf(BusinessException.class);
        service.recordVerifiedMaterialReport(id, "材料分类与提取完成");
        service.ensureReportCompleted(id);
        var reopened = service.require(id);
        assertThat(reopened.workflow().currentStepOrder()).isEqualTo(8);
        assertThat(reopened.steps().stream().filter(s -> List.of("QUOTE_UPLOAD", "TENDER_DOCUMENT", "COLLUSION").contains(s.stepCode())))
                .noneSatisfy(s -> assertThat(s.status()).isEqualTo(ProcurementReviewStep.COMPLETED));
    }

    private static ProcurementProject project(UUID id) {
        return new ProcurementProject(id, "PR-TEST", "测试采购项目", "M1001", "办公设备",
                new BigDecimal("100.00"), null, "DRAFT", NOW);
    }

    private record SingleProjectQueryPort(ProcurementProject project) implements ProcurementProjectQueryPort {
        @Override public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(List.of(project), 1);
        }
        @Override public Optional<ProcurementProject> findById(UUID id) {
            return project.id().equals(id) ? Optional.of(project) : Optional.empty();
        }
    }

    private static final class MemoryWorkflowRepository implements ProcurementReviewWorkflowRepository {
        private Snapshot snapshot;

        @Override public void create(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
            snapshot = new Snapshot(workflow, steps);
        }
        @Override public void update(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
            snapshot = new Snapshot(workflow, steps);
        }
        @Override public Optional<Snapshot> findByProjectId(UUID projectId) {
            return snapshot != null && snapshot.workflow().projectId().equals(projectId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
    }
}
