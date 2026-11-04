package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.ItemResult;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementAgentReviewServiceTest {

    @Test
    void referenceReviewIsIndependentOfWorkflowState() {
        for (String status : List.of("DRAFT", "REVIEWING", "REJECTED", "SUBMITTED", "APPROVED")) {
            UUID id = UUID.randomUUID();
            var result = new ProcurementAgentReviewService(new ProcurementProjectQueryService(new StubPort(
                    project(id, "办公设备采购", "M1001", "办公设备", new BigDecimal("100.00"), status)))).review(id);
            assertThat(result.riskLevel()).as(status).isEqualTo("INFO");
            assertThat(result.items()).noneMatch(item -> "PROJECT_STATUS".equals(item.checkType()));
        }
    }

    @Test
    void oldSnapshotProjectionDropsOnlyWorkflowRiskAndPreservesRealRisks() {
        var workflow = new ProcurementAgentReviewService.ReviewItem("PROJECT_STATUS", "采购项目状态",
                "WARNING", "WARNING", "尚未提交审批", "请确认审批状态", null, null, null, null, false);
        var real = new ProcurementAgentReviewService.ReviewItem("BUDGET_AMOUNT", "预算金额",
                "FAILED", "CRITICAL", "预算金额缺失", "请补充有效预算金额", null, null, null, null, false);
        UUID id = UUID.randomUUID();
        var saved = new ProcurementAgentReviewService.ReviewResult(id, "fixture", "合成项目", "CRITICAL",
                "审批及金额", "旧摘要包含审批信息", Instant.now(), List.of(workflow, real));
        var view = ProcurementAgentReviewService.referenceResult(saved);
        assertThat(view.riskLevel()).isEqualTo("CRITICAL");
        assertThat(view.items()).containsExactly(real);
        assertThat(view.suggestion()).isEqualTo("请补充有效预算金额");
        assertThat(view.aiSummary()).isNull();
        assertThat(saved.items()).containsExactly(workflow, real);
        var statusOnly = new ProcurementAgentReviewService.ReviewResult(id, "fixture", "合成项目", "WARNING",
                "尚未审批", null, Instant.now(), List.of(workflow));
        assertThat(ProcurementAgentReviewService.referenceResult(statusOnly).riskLevel()).isEqualTo("INFO");
    }

    @Test
    void deterministicReviewReturnsCriticalForInvalidBudget() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                BigDecimal.ZERO, "DRAFT");
        ProcurementProjectQueryService query = new ProcurementProjectQueryService(
                new StubPort(project));

        ProcurementAgentReviewService.ReviewResult result =
                new ProcurementAgentReviewService(query).review(id);

        assertThat(result.riskLevel()).isEqualTo("CRITICAL");
        assertThat(result.items()).extracting(ProcurementAgentReviewService.ReviewItem::checkType)
                .contains("BUDGET_AMOUNT").doesNotContain("PROJECT_STATUS");
        assertThat(result.suggestion()).contains("预算金额").doesNotContain("审批状态");
    }

    @Test
    void deterministicReviewReturnsInfoForApprovedCompleteProject() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "APPROVED");

        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project))).review(id);

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("PASSED"));
        assertThat(result.items()).extracting(ProcurementAgentReviewService.ReviewItem::checkType)
                .doesNotContain("SUPPLIER_NAME");
    }

    @Test
    void aiEnrichesExplanationAndSummaryWithoutChangingRuleRisk() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "DRAFT");
        ProcurementAgentReviewAiPort ai = request -> new ProcurementAgentReviewAiPort.Result(
                true, "test-model", 12L, "AI 整体预审结论",
                request.items().stream()
                        .map(item -> new ItemResult(item.itemId(),
                                "AI 解释：" + item.checkName(), "AI 建议：请结合项目上下文处理"))
                        .toList());

        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project)), ai).review(id);

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.aiSummary()).isEqualTo("AI 整体预审结论");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.aiExplanation()).startsWith("AI 解释：");
            assertThat(item.aiSuggestion()).startsWith("AI 建议：");
        });
        assertThat(result.items()).extracting(ProcurementAgentReviewService.ReviewItem::status)
                .containsExactly("PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED");
    }

    @Test
    void aiSummaryFallsBackToAvailableItemExplanationWhenSummaryItemIsMissing() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "APPROVED");
        ProcurementAgentReviewAiPort ai = request -> new ProcurementAgentReviewAiPort.Result(
                true, "test-model", 12L, null,
                List.of(new ItemResult(
                        request.items().get(0).itemId(), "AI 已结合项目上下文完成解释。", null)));

        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project)), ai).review(id);

        assertThat(result.aiSummary()).isEqualTo("AI 已结合项目上下文完成解释。");
        assertThat(result.items().get(0).aiExplanation()).isEqualTo("AI 已结合项目上下文完成解释。");
    }

    @Test
    void unavailableAiFallsBackToRuleResult() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "APPROVED");
        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project)),
                request -> ProcurementAgentReviewAiPort.Result.unavailable()).review(id);

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.aiSummary()).isNull();
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.aiExplanation()).isNull();
            assertThat(item.aiSuggestion()).isNull();
        });
    }

    @Test
    void timedOutAiFallsBackToRuleResult() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "APPROVED");
        ProcurementAgentReviewAiPort slowAi = request -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ProcurementAgentReviewAiPort.Result(true, "slow-model", 150L,
                    "不应被使用", List.of());
        };

        long startedAt = System.nanoTime();
        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project)), slowAi, null, 20L).review(id);

        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(1_000L);
        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.aiSummary()).isNull();
    }

    @Test
    void unreasonableValuesAreWarningsThatRequireConfirmation() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(id, "BA-WARN", "1", "1001", "1",
                new BigDecimal("100.00"), null, "DRAFT", Instant.now(), "1", "货物",
                "公开招标", null, "1", null);

        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project))).review(id);

        assertThat(result.riskLevel()).isEqualTo("WARNING");
        assertThat(result.items()).filteredOn(ProcurementAgentReviewService.ReviewItem::needsConfirmation)
                .extracting(ProcurementAgentReviewService.ReviewItem::checkType)
                .containsExactly("PROJECT_NAME", "SUBJECT_NAME", "DEPARTMENT_NAME", "APPLICANT_NAME");
        assertThat(result.items()).filteredOn(ProcurementAgentReviewService.ReviewItem::needsConfirmation)
                .allSatisfy(item -> assertThat(item.status()).isEqualTo("PENDING_CONFIRMATION"));
    }

    @Test
    void numericBudgetCodesAndShortChineseSubjectNamesAreValid() {
        UUID id = UUID.randomUUID();
        var result = new ProcurementAgentReviewService(new ProcurementProjectQueryService(new StubPort(
                project(id, "办公设备采购", "31002", "设备", new BigDecimal("100.00"), "DRAFT")))).review(id);
        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.items()).allMatch(item -> "PASSED".equals(item.status()));
        var invalid = new ProcurementAgentReviewService(new ProcurementProjectQueryService(new StubPort(
                project(id, "办公设备采购", "...", "1234", new BigDecimal("100.00"), "DRAFT")))).review(id);
        assertThat(invalid.items()).filteredOn(item -> item.checkType().startsWith("SUBJECT_"))
                .allMatch(ProcurementAgentReviewService.ReviewItem::needsConfirmation);
    }

    @Test
    void aiMayRaiseAdvisoryConfirmationWithoutChangingAValidFieldToFailure() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = project(id, "办公设备采购", "M1001", "办公设备",
                new BigDecimal("100.00"), "APPROVED");
        ProcurementAgentReviewAiPort ai = request -> new ProcurementAgentReviewAiPort.Result(
                true, "test-model", 1, null,
                List.of(new ItemResult(request.items().get(0).itemId(), "名称语义需要人工复核",
                        "请确认名称与实际采购内容一致", true)));

        ProcurementAgentReviewService.ReviewResult result = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubPort(project)), ai).review(id);

        assertThat(result.items().get(0).status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(result.items().get(0).riskLevel()).isEqualTo("WARNING");
        assertThat(result.items().get(0).needsConfirmation()).isTrue();
    }

    private static ProcurementProject project(UUID id, String name, String subjectCode, String subjectName,
                                              BigDecimal amount, String status) {
        return new ProcurementProject(id, "BA-TEST", name, subjectCode, subjectName, amount, null, status,
                Instant.now(), "采购部", "货物", "公开招标", null, "张三", null);
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
}
