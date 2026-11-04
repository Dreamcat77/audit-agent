package com.fuquan.audit.budget.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetControllerResponseStateTest {

    @Test
    void checksAfterCurrentPauseRemainPending() {
        UUID taskId = UUID.randomUUID();
        BudgetReviewTask task = waitingTask(taskId, 2, "AMOUNT_FORMAT");
        BudgetReviewItem item = item(taskId, 2, "FAILED", "NOT_CHECKED", "FAILED");

        BudgetController.ReviewItemResponse response =
                BudgetController.ReviewItemResponse.from(task, item);

        assertThat(response.currentStep()).isEqualTo(2);
        assertThat(response.checks().get(0).status()).isEqualTo("PASSED");
        assertThat(response.checks().get(1).status()).isEqualTo("FAILED");
        assertThat(response.checks().subList(2, 10))
                .allMatch(check -> check.status().equals("PENDING"));
    }

    @Test
    void staleResultsOnUnprocessedRowsAreNotExposed() {
        UUID taskId = UUID.randomUUID();
        BudgetReviewTask task = waitingTask(taskId, 2, "AMOUNT_FORMAT");
        BudgetReviewItem staleItem = item(taskId, 3, "PASSED", "OVER_BUDGET", "FAILED");

        BudgetController.ReviewItemResponse response =
                BudgetController.ReviewItemResponse.from(task, staleItem);

        assertThat(response.currentStep()).isZero();
        assertThat(response.checks())
                .allMatch(check -> check.status().equals("PENDING"));
        assertThat(response.checks())
                .noneMatch(check -> check.status().equals("PASSED")
                        || check.status().equals("FAILED"));
    }

    @Test
    void staleBudgetComparisonIsSkippedWhenALaterStepIsPaused() {
        UUID taskId = UUID.randomUUID();
        BudgetReviewTask task = waitingTask(taskId, 4, "PROJECT_DEPARTMENT");
        BudgetReviewItem item = unmatchedItem(taskId, 4);

        BudgetController.ReviewItemResponse response =
                BudgetController.ReviewItemResponse.from(task, item);

        assertThat(response.currentStep()).isEqualTo(9);
        assertThat(response.checks().get(5).status()).isEqualTo("SKIPPED");
        assertThat(response.checks().get(5).errorDetail())
                .contains("已跳过");
        assertThat(response.checks().get(8).status()).isEqualTo("FAILED");
    }

    private static BudgetReviewTask waitingTask(
            UUID taskId, int pendingRowNumber, String pendingStep) {
        Instant now = Instant.now();
        return new BudgetReviewTask(
                taskId, "budget.xlsx", "text/plain", 1, null,
                "WAITING_CONFIRMATION", 3, null, "2026", "PROCUREMENT",
                1, pendingRowNumber, pendingStep, "待确认异常", now, now);
    }

    private static BudgetReviewItem item(
            UUID taskId,
            int rowNumber,
            String amountFormatStatus,
            String amountStatus,
            String projectCodeStatus) {
        Instant now = Instant.now();
        return new BudgetReviewItem(
                UUID.randomUUID(), taskId, rowNumber,
                "M1001", "办公设备采购", new BigDecimal("100.00"),
                "供应商", "P001",
                "M1001", "办公设备采购", new BigDecimal("150.00"),
                "供应商", "MATCHED", "EXACT", 0,
                amountStatus, new BigDecimal("-50.00"), null, null, "CRITICAL",
                null, null, null,
                "PASSED", amountFormatStatus, "PASSED", null,
                projectCodeStatus, "FAILED", "FAILED",
                List.of("测试异常"), now);
    }

    private static BudgetReviewItem unmatchedItem(UUID taskId, int rowNumber) {
        Instant now = Instant.now();
        return new BudgetReviewItem(
                UUID.randomUUID(), taskId, rowNumber,
                "M9999", "不存在的预算科目", new BigDecimal("10000.00"),
                "供应商A", "P999",
                null, null, null,
                null, "UNMATCHED", null, null,
                "NOT_CHECKED", null, null, "科目匹配失败：未找到预算批复科目",
                "CRITICAL", null, null, null,
                "PASSED", "PASSED", "PASSED", null,
                "PASSED", "FAILED", "NOT_CHECKED",
                List.of("科目匹配失败：未找到预算批复科目"), now);
    }
}
