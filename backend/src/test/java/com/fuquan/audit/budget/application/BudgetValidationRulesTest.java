package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewItemRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewTaskRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetValidationConfirmLogRepository;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetValidationRulesTest {

    private final FlowDebugLogger logger = new FlowDebugLogger(new ObservabilityProperties());

    @Test
    void eachRequiredFieldIsReported() {
        assertThat(review(line(null, "办公设备采购", "10.00", "供应商", "P001"))
                .missingFields()).contains("科目编码");
        assertThat(review(line("M1001", null, "10.00", "供应商", "P001"))
                .missingFields()).contains("科目名称");
        assertThat(review(line("M1001", "办公设备采购", null, "供应商", "P001"))
                .missingFields()).contains("申报金额");
        assertThat(review(line("M1001", "办公设备采购", "10.00", null, "P001"))
                .missingFields()).contains("供应商名称");
        assertThat(review(line("M1001", "办公设备采购", "10.00", "供应商", null))
                .missingFields()).contains("项目编号");
    }

    @Test
    void amountZeroIsWarning() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "0", "供应商", "P001"));
        assertThat(item.amountFormatErrors()).contains("金额为0");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void negativeAmountIsWarning() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "-1", "供应商", "P001"));
        assertThat(item.amountFormatErrors()).contains("金额为负数");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsWarning() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "1.001", "供应商", "P001"));
        assertThat(item.amountFormatErrors()).contains("小数位超过2位");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void amountWithLettersIsWarning() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "10abc", "供应商", "P001"));
        assertThat(item.amountFormatErrors()).contains("含非数字字符");
        assertThat(item.abnormalReasons()).contains("金额格式非法：含非数字字符");
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void financialUppercaseAmountPassesFormatWithoutLosingJiaoFen() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "壹拾贰万叁仟肆佰伍拾陆元柒角捌分整", "供应商", "P001"));
        assertThat(item.declaredAmount()).isEqualByComparingTo("123456.78");
        assertThat(item.amountFormatStatus()).isEqualTo("PASSED");
        assertThat(item.amountFormatErrors()).isNullOrEmpty();
    }

    @Test
    void invalidSubjectCodeIsWarning() {
        BudgetReviewItem item = review(line("中文", "办公设备采购", "10.00", "供应商", "P001"));
        assertThat(item.codeFormatError()).contains("格式异常");
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void missingSupplierStopsMatchingAndAmountCheck() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "10.00", null, "P001"));
        assertThat(item.missingFields()).isEqualTo("供应商名称");
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void invalidAmountDoesNotCompareAgainstApproval() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "数据异常", "供应商", "P001"));
        assertThat(item.amountFormatErrors()).contains("含非数字字符");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.differenceAmount()).isNull();
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void invalidSubjectCodeStillRunsOtherChecks() {
        BudgetReviewItem item = review(line("中文", "办公设备采购", "10.00", "供应商", "P001"));
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.approvalAmount()).isNotNull();
        assertThat(item.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void differenceAmountKeepsTwoDecimalScaleAndSign() {
        BudgetReviewItem overBudget = review(line("M1001", "办公设备采购", "160000", "供应商", "P001"));
        assertThat(overBudget.differenceAmount()).isEqualByComparingTo("159900.00");
        assertThat(overBudget.differenceAmount().scale()).isEqualTo(2);

        BudgetReviewItem withinBudget = review(line("M1001", "办公设备采购", "99.99", "供应商", "P001"));
        assertThat(withinBudget.amountStatus()).isEqualTo("PASSED");
        assertThat(withinBudget.differenceAmount()).isEqualByComparingTo("-0.01");
        assertThat(withinBudget.differenceAmount().scale()).isEqualTo(2);
    }

    @Test
    void overBudgetIsCritical() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "100.01", "供应商", "P001"));
        assertThat(item.amountStatus()).isEqualTo("OVER_BUDGET");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    void differenceAmountUsesDeclaredMinusApprovalForLargeAmounts() {
        BudgetReviewItem surplus = review(
                line("M1001", "办公设备采购", "1000000", "供应商", "P001"),
                new BigDecimal("1200000"));
        BudgetReviewItem overBudget = review(
                line("M1001", "办公设备采购", "1600000", "供应商", "P001"),
                new BigDecimal("1500000"));
        BudgetReviewItem exact = review(
                line("M1001", "办公设备采购", "1500000", "供应商", "P001"),
                new BigDecimal("1500000"));

        assertThat(surplus.differenceAmount()).isEqualByComparingTo("-200000.00");
        assertThat(overBudget.differenceAmount()).isEqualByComparingTo("100000.00");
        assertThat(exact.differenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void overBudgetAndSupplierMismatchAreConfirmedAsOneRow() {
        BudgetReviewService service = service(List.of(
                line("M1001", "办公设备采购", "1600000", "其他供应商", "P001")),
                new BigDecimal("1500000"));
        BudgetReviewTask task = service.uploadAndParse(
                "over-budget-before-supplier.txt", "text/plain", 10,
                new ByteArrayInputStream("line".getBytes(StandardCharsets.UTF_8)));

        service.review(task.id());
        BudgetReviewItem initial = service.findItems(task.id()).get(0);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("BUDGET_COMPARE");
        assertThat(initial.abnormalReasons())
                .contains("超预算：申报金额超过预算批复金额")
                .doesNotContain("供应商名称与预算批复不一致");

        BudgetReviewService.ConfirmationProgress progress = service.confirmException(
                task.id(), 2, "BUDGET_COMPARE", "申报金额超过预算批复金额", "已核实",
                "budget-test-user", "Budget Test");

        assertThat(progress.updatedItem()).isNotNull();
        assertThat(progress.nextItem()).isNotNull();
        assertThat(progress.updatedItem().rowNumber()).isEqualTo(2);
        assertThat(progress.nextStep()).isEqualTo("SUPPLIER_NAME");
        assertThat(progress.executionStatus()).isEqualTo("PAUSED");
        assertThat(service.requireTask(task.id()).status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(progress.updatedItem().exceptionType()).isEqualTo("SUPPLIER_MISMATCH");

        BudgetReviewService.ConfirmationProgress completed = service.confirmRow(
                task.id(), 2, "已核实供应商异常", "budget-test-user", "Budget Test");

        assertThat(completed.executionStatus()).isEqualTo("COMPLETED");
        assertThat(completed.hasMoreException()).isFalse();
        assertThat(completed.nextStep()).isNull();
        assertThat(service.requireTask(task.id()).status()).isEqualTo("REVIEWED");
        assertThat(completed.updatedItem().matchStatus()).isEqualTo("MATCHED");
        assertThat(completed.updatedItem().projectCodeStatus()).isEqualTo("PASSED");
        assertThat(completed.updatedItem().projectDepartmentStatus()).isEqualTo("PASSED");
        assertThat(completed.updatedItem().budgetYearValidityStatus()).isEqualTo("PASSED");
    }

    @Test
    void staleConfirmationStepCannotConfirmTheNewPendingStep() {
        BudgetReviewService service = service(List.of(
                line("M1001", "办公设备采购", "1600000", "其他供应商", "P001")),
                new BigDecimal("1500000"));
        BudgetReviewTask task = service.uploadAndParse(
                "stale-confirmation.txt", "text/plain", 10,
                new ByteArrayInputStream("line".getBytes(StandardCharsets.UTF_8)));
        service.review(task.id());
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("BUDGET_COMPARE");

        assertThatThrownBy(() -> service.confirmException(
                task.id(), 2, "SUPPLIER_NAME", "过期页面提交", "已核实",
                "budget-test-user", "Budget Test"))
                .hasMessageContaining("待确认步骤已变化");

        assertThat(service.findConfirmLogs(task.id())).isEmpty();
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("BUDGET_COMPARE");
    }

    @Test
    void confirmingBudgetExceptionContinuesTheSameRowBeforeCompleting() {
        BudgetReviewService service = service(
                List.of(line("M1001", "办公设备采购", "160.00", "供应商", "P001")),
                new BigDecimal("150.00"),
                List.of(projectConfig("P001", "PROCUREMENT")),
                List.of(yearConfig("M1001", 2024, 2026)));
        BudgetReviewTask task = service.uploadAndParse(
                "budget-continue.txt", "text/plain", 10,
                new ByteArrayInputStream("budget-continue".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");

        service.review(task.id());
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("BUDGET_COMPARE");

        BudgetReviewService.ConfirmationProgress progress = service.confirmRow(
                task.id(), 2, "已核实超预算", "budget-test-user", "Budget Test");

        assertThat(progress.executionStatus()).isEqualTo("COMPLETED");
        assertThat(progress.hasMoreException()).isFalse();
        assertThat(service.requireTask(task.id()).status()).isEqualTo("REVIEWED");
        assertThat(progress.updatedItem()).isNotNull();
        assertThat(progress.updatedItem().amountStatus()).isEqualTo("OVER_BUDGET");
        assertThat(progress.updatedItem().matchStatus()).isEqualTo("MATCHED");
        assertThat(progress.updatedItem().projectCodeStatus()).isEqualTo("PASSED");
        assertThat(progress.updatedItem().projectDepartmentStatus()).isEqualTo("PASSED");
        assertThat(progress.updatedItem().budgetYearValidityStatus()).isEqualTo("PASSED");
        assertThat(progress.updatedItem().abnormalReasons())
                .containsExactly("超预算：申报金额超过预算批复金额");
    }

    @Test
    void subjectMatchFailureIsCritical() {
        BudgetReviewItem item = review(line("M1001", "完全不存在的科目", "10.00", "供应商", "P001"));
        assertThat(item.matchStatus()).isEqualTo("UNMATCHED");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    void allChecksPassWithInfoRisk() {
        BudgetReviewItem item = review(line("M1001", "办公设备采购", "10.00", "供应商", "P001"));
        assertThat(item.riskLevel()).isEqualTo("INFO");
        assertThat(item.abnormalReasons()).isEmpty();
    }

    @Test
    void mixedRowsKeepIndependentResults() {
        BudgetReviewService service = service(List.of(
                line("M1001", "办公设备采购", "10.00", "供应商", "P001"),
                new BudgetMaterialLine(
                        3, "M1001", "办公设备采购", "100.01",
                        BudgetTextNormalizer.amount("100.01"), "供应商", "P002")));
        BudgetReviewTask task = service.uploadAndParse(
                "mixed.txt", "text/plain", 10,
                new ByteArrayInputStream("mixed".getBytes(StandardCharsets.UTF_8)));
        service.review(task.id());
        assertThat(service.requireTask(task.id()).status()).isEqualTo("WAITING_CONFIRMATION");
        service.confirmException(
                task.id(), 3, "BUDGET_COMPARE", "超预算", "已核实",
                "budget-test-user", "Budget Test");
        assertThat(service.findItems(task.id()).stream()
                .map(BudgetReviewItem::riskLevel))
                .containsExactlyInAnyOrder("INFO", "CRITICAL");
    }

    @Test
    void confirmationContinuesWithNextRowAfterOneConfirmation() {
        BudgetReviewService service = service(List.of(
                line("M1001", "办公设备采购", "100.01", "供应商", null)));
        BudgetReviewTask task = service.uploadAndParse(
                "same-row.txt", "text/plain", 10,
                new ByteArrayInputStream("same-row".getBytes(StandardCharsets.UTF_8)));

        service.review(task.id());
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("REQUIRED_FIELDS");

        service.confirmException(
                task.id(), 2, "ROW_REVIEW", "必填字段缺失：projectCode", "已核实",
                "budget-test-user", "Budget Test");
        assertThat(service.requireTask(task.id()).status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(service.findItems(task.id())).hasSize(1);
        assertThat(service.findItems(task.id()).get(0).amountStatus()).isEqualTo("OVER_BUDGET");
    }

    @Test
    void duplicateProjectCodePausesAtProjectCodeStep() {
        BudgetReviewService service = service(
                List.of(
                        line(2, "M1001", "办公设备采购", "90.00", "供应商", "P001"),
                        line(3, "M1001", "办公设备采购", "90.00", "供应商", "P001")),
                new BigDecimal("100.00"),
                List.of(projectConfig("P001", "PROCUREMENT")),
                List.of(yearConfig("M1001", 2024, 2026)));

        BudgetReviewTask task = service.uploadAndParse(
                "duplicate-project.txt", "text/plain", 10,
                new ByteArrayInputStream("duplicate".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("PROJECT_CODE");
        assertThat(item.projectCodeStatus()).isEqualTo("FAILED");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
        assertThat(item.abnormalReasons().get(0)).contains("项目编号重复：第2、3行");
    }

    @Test
    void confirmationContinuesTheSameRowThroughRemainingProjectChecks() {
        BudgetReviewService service = service(
                List.of(
                        line(2, "M1001", "办公设备采购", "90.00", "供应商", "P001"),
                        line(3, "M1001", "办公设备采购", "90.00", "供应商", "P001")),
                new BigDecimal("100.00"),
                List.of(projectConfig("P001", "FINANCE")),
                List.of(yearConfig("M1001", 2024, 2026)));

        BudgetReviewTask task = service.uploadAndParse(
                "continue-same-row.txt", "text/plain", 10,
                new ByteArrayInputStream("continue-same-row".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");
        service.review(task.id());

        assertThat(service.requireTask(task.id()).pendingRowNumber()).isEqualTo(2);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("PROJECT_CODE");

        BudgetReviewService.ConfirmationProgress departmentProgress = service.confirmRow(
                task.id(), 2, "已核实项目编号异常", "budget-test-user", "Budget Test");

        assertThat(service.requireTask(task.id()).pendingRowNumber()).isEqualTo(2);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("PROJECT_DEPARTMENT");
        assertThat(departmentProgress.updatedItem()).isNotNull();
        assertThat(departmentProgress.updatedItem().projectDepartmentStatus()).isEqualTo("FAILED");
        assertThat(departmentProgress.updatedItem().budgetYearValidityStatus()).isEqualTo("NOT_CHECKED");

        BudgetReviewService.ConfirmationProgress yearProgress = service.confirmRow(
                task.id(), 2, "已核实项目归属异常", "budget-test-user", "Budget Test");

        assertThat(service.requireTask(task.id()).status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(service.requireTask(task.id()).pendingRowNumber()).isEqualTo(3);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("PROJECT_CODE");
        assertThat(yearProgress.updatedItem()).isNotNull();
        assertThat(yearProgress.updatedItem().budgetYearValidityStatus()).isEqualTo("PASSED");
        assertThat(yearProgress.nextExceptionRow()).isEqualTo(3);
    }

    @Test
    void confirmingBudgetYearExceptionDoesNotRestartTheRow() {
        BudgetReviewService service = service(
                List.of(line("M1001", "办公设备采购", "90.00", "供应商", "P002")),
                new BigDecimal("100.00"),
                List.of(projectConfig("P002", "FINANCE")),
                List.of(yearConfig("M9999", 2024, 2026)));

        BudgetReviewTask task = service.uploadAndParse(
                "confirm-year-terminal.txt", "text/plain", 10,
                new ByteArrayInputStream("confirm-year-terminal".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");
        service.review(task.id());

        assertThat(service.requireTask(task.id()).pendingStep())
                .isEqualTo("PROJECT_DEPARTMENT");
        UUID itemId = service.findItems(task.id()).get(0).id();

        BudgetReviewService.ConfirmationProgress departmentProgress = service.confirmRow(
                task.id(), 2, "已核实项目归属异常", "budget-test-user", "Budget Test");

        assertThat(service.requireTask(task.id()).pendingStep())
                .isEqualTo("BUDGET_YEAR_VALIDITY");
        assertThat(departmentProgress.updatedItem().projectDepartmentStatus())
                .isEqualTo("FAILED");
        assertThat(departmentProgress.updatedItem().budgetYearValidityStatus())
                .isEqualTo("FAILED");
        assertThat(departmentProgress.updatedItem().id()).isEqualTo(itemId);

        BudgetReviewService.ConfirmationProgress yearProgress = service.confirmRow(
                task.id(), 2, "已核实年度异常", "budget-test-user", "Budget Test");

        assertThat(yearProgress.executionStatus()).isEqualTo("COMPLETED");
        assertThat(service.requireTask(task.id()).status()).isEqualTo("REVIEWED");
        assertThat(service.requireTask(task.id()).pendingStep()).isNull();
        assertThat(yearProgress.updatedItem().projectDepartmentStatus())
                .isEqualTo("FAILED");
        assertThat(yearProgress.updatedItem().budgetYearValidityStatus())
                .isEqualTo("FAILED");
        assertThat(yearProgress.updatedItem().id()).isEqualTo(itemId);
        assertThat(service.findConfirmLogs(task.id())).hasSize(2);
    }

    @Test
    void confirmationLogSurvivesFailureDuringResume() {
        boolean[] failBlacklist = {false};
        BlacklistCheckPort blacklist = command -> {
            if (failBlacklist[0]) {
                throw new IllegalStateException("resume failure");
            }
            return new BlacklistCheckPort.CheckOutcome(
                    command.batchId(), command.supplierName(), command.supplierCode(),
                    false, List.of(), null);
        };
        BudgetReviewService service = service(
                List.of(line("M1001", "办公设备采购", "160.00", "供应商", "P001")),
                new BigDecimal("100.00"), List.of(), List.of(), blacklist);
        BudgetReviewTask task = service.uploadAndParse(
                "resume-failure.txt", "text/plain", 10,
                new ByteArrayInputStream("resume-failure".getBytes(StandardCharsets.UTF_8)));
        service.review(task.id());
        failBlacklist[0] = true;

        assertThatThrownBy(() -> service.confirmException(
                task.id(), 2, "BUDGET_COMPARE", "超预算", "已核实",
                "budget-test-user", "Budget Test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("resume failure");
        assertThat(service.findConfirmLogs(task.id())).hasSize(1);
        assertThat(service.requireTask(task.id()).status()).isEqualTo("FAILED");
    }

    @Test
    void projectDepartmentMismatchPausesAtDepartmentStep() {
        BudgetReviewService service = service(
                List.of(line("M1001", "办公设备采购", "90.00", "供应商", "P001")),
                new BigDecimal("100.00"),
                List.of(projectConfig("P001", "FINANCE")),
                List.of(yearConfig("M1001", 2024, 2026)));

        BudgetReviewTask task = service.uploadAndParse(
                "department-mismatch.txt", "text/plain", 10,
                new ByteArrayInputStream("department".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("PROJECT_DEPARTMENT");
        assertThat(item.projectDepartmentStatus()).isEqualTo("FAILED");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
        assertThat(item.abnormalReasons().get(0)).contains("项目不属于当前部门");
    }

    @Test
    void invalidBudgetYearPausesAtYearValidityStep() {
        BudgetReviewService service = service(
                List.of(line("M1001", "办公设备采购", "90.00", "供应商", "P001")),
                new BigDecimal("100.00"),
                List.of(projectConfig("P001", "PROCUREMENT")),
                List.of(yearConfig("M1001", 2024, 2025)));

        BudgetReviewTask task = service.uploadAndParse(
                "invalid-year.txt", "text/plain", 10,
                new ByteArrayInputStream("invalid-year".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("BUDGET_YEAR_VALIDITY");
        assertThat(item.budgetYearValidityStatus()).isEqualTo("FAILED");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
        assertThat(item.abnormalReasons().get(0)).contains("已停用").contains("2025");
    }

    @Test
    void unmatchedSubjectSkipsBudgetComparisonInsteadOfRemainingPending() {
        BudgetReviewService service = service(
                List.of(line("M9999", "不存在的预算科目", "10000", "供应商", "P999")),
                new BigDecimal("15000"),
                List.of(projectConfig("P001", "PROCUREMENT")),
                List.of(yearConfig("M1001", 2024, 2026)));
        BudgetReviewTask task = service.uploadAndParse(
                "unmatched-budget.txt", "text/plain", 10,
                new ByteArrayInputStream("unmatched-budget".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");

        service.review(task.id());
        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("SUBJECT_NAME");

        BudgetReviewService.ConfirmationProgress progress = service.confirmRow(
                task.id(), 2, "已核实科目匹配异常", "budget-test-user", "Budget Test");

        assertThat(service.requireTask(task.id()).pendingStep())
                .isEqualTo("PROJECT_DEPARTMENT");
        assertThat(progress.updatedItem().amountStatus()).isEqualTo("SKIPPED");
    }

    @Test
    void forbiddenCategoryKeywordPausesWithCriticalRiskAndSuggestion() {
        BudgetCategoryRule rule = categoryRule("30201", "办公费", "打印纸", "电脑", "31002 办公设备购置");
        BudgetReviewService service = categoryService(
                new BudgetMaterialLine(
                        2, "30201", "电脑采购", "10.00",
                        BudgetTextNormalizer.amount("10.00"), "供应商", "P001"),
                new BudgetSubjectMapping(
                        UUID.randomUUID(), "30201", "电脑采购", "30201", "电脑采购",
                        new BigDecimal("100.00"), "2026", "供应商", "ACTIVE"),
                List.of(rule));
        BudgetReviewTask task = service.uploadAndParse(
                "category-forbidden.txt", "text/plain", 10,
                new ByteArrayInputStream("category".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");

        service.review(task.id());
        BudgetReviewItem item = service.findItems(task.id()).get(0);

        assertThat(service.requireTask(task.id()).pendingStep()).isEqualTo("CATEGORY_ATTRIBUTION");
        assertThat(item.categoryAttributionStatus()).isEqualTo("FAILED");
        assertThat(item.categorySuggestion()).isEqualTo("31002 办公设备购置");
        assertThat(item.riskLevel()).isEqualTo("CRITICAL");
        assertThat(item.abnormalReasons()).anyMatch(reason ->
                reason.contains("科目分类归属不匹配")
                        && reason.contains("电脑")
                        && reason.contains("31002 办公设备购置"));
    }

    @Test
    void allowedCategoryKeywordPassesAndDoesNotPause() {
        BudgetCategoryRule rule = categoryRule("30201", "办公费", "打印纸", "电脑", "31002 办公设备购置");
        BudgetReviewService service = categoryService(
                new BudgetMaterialLine(
                        2, "30201", "购买打印纸", "10.00",
                        BudgetTextNormalizer.amount("10.00"), "供应商", "P001"),
                new BudgetSubjectMapping(
                        UUID.randomUUID(), "30201", "购买打印纸", "30201", "购买打印纸",
                        new BigDecimal("100.00"), "2026", "供应商", "ACTIVE"),
                List.of(rule));
        BudgetReviewTask task = service.uploadAndParse(
                "category-allowed.txt", "text/plain", 10,
                new ByteArrayInputStream("category".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");

        service.review(task.id());
        BudgetReviewItem item = service.findItems(task.id()).get(0);

        assertThat(service.requireTask(task.id()).status()).isEqualTo("REVIEWED");
        assertThat(item.categoryAttributionStatus()).isEqualTo("PASSED");
        assertThat(item.riskLevel()).isEqualTo("INFO");
    }

    @Test
    void confirmingCategoryExceptionContinuesToBudgetAndCompletesRow() {
        BudgetCategoryRule rule = categoryRule("30201", "办公费", "打印纸", "电脑", "31002 办公设备购置");
        BudgetReviewService service = categoryService(
                new BudgetMaterialLine(
                        2, "30201", "电脑采购", "10.00",
                        BudgetTextNormalizer.amount("10.00"), "供应商", "P001"),
                new BudgetSubjectMapping(
                        UUID.randomUUID(), "30201", "电脑采购", "30201", "电脑采购",
                        new BigDecimal("100.00"), "2026", "供应商", "ACTIVE"),
                List.of(rule));
        BudgetReviewTask task = service.uploadAndParse(
                "category-confirm.txt", "text/plain", 10,
                new ByteArrayInputStream("category".getBytes(StandardCharsets.UTF_8)),
                "2026", "PROCUREMENT");

        service.review(task.id());
        BudgetReviewService.ConfirmationProgress progress = service.confirmRow(
                task.id(), 2, "已核实分类归属", "budget-test-user", "Budget Test");

        assertThat(progress.executionStatus()).isEqualTo("COMPLETED");
        assertThat(service.requireTask(task.id()).status()).isEqualTo("REVIEWED");
        assertThat(progress.updatedItem().categoryAttributionStatus()).isEqualTo("FAILED");
        assertThat(progress.updatedItem().amountStatus()).isEqualTo("PASSED");
        assertThat(service.findConfirmLogs(task.id())).hasSize(1);
        assertThat(service.findConfirmLogs(task.id()).get(0).confirmStep())
                .isEqualTo("SUBJECT_CONSISTENCY");
    }

    private BudgetReviewItem review(BudgetMaterialLine line) {
        return review(line, new BigDecimal("100.00"));
    }

    private BudgetReviewItem review(BudgetMaterialLine line, BigDecimal approvalAmount) {
        BudgetReviewService service = service(List.of(line), approvalAmount);
        BudgetReviewTask task = service.uploadAndParse(
                "line.txt", "text/plain", 10,
                new ByteArrayInputStream("line".getBytes(StandardCharsets.UTF_8)));
        service.review(task.id());
        return service.findItems(task.id()).get(0);
    }

    private BudgetReviewService service(List<BudgetMaterialLine> lines) {
        return service(lines, new BigDecimal("100.00"));
    }

    private BudgetReviewService service(List<BudgetMaterialLine> lines, BigDecimal approvalAmount) {
        return service(lines, approvalAmount, List.of(), List.of());
    }

    private BudgetReviewService service(
            List<BudgetMaterialLine> lines,
            BigDecimal approvalAmount,
            List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs) {
        return service(
                lines, approvalAmount, projectConfigs, yearValidityConfigs,
                command -> new BlacklistCheckPort.CheckOutcome(
                        command.batchId(), command.supplierName(), command.supplierCode(),
                        false, List.of(), null));
    }

    private BudgetReviewService service(
            List<BudgetMaterialLine> lines,
            BigDecimal approvalAmount,
            List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs,
            BlacklistCheckPort blacklistCheckPort) {
        BudgetSubjectMappingRepository mappings = new BudgetSubjectMappingRepository() {
            @Override
            public List<BudgetSubjectMapping> findBySystemSubjectCode(String code) {
                return List.of();
            }

            @Override
            public List<BudgetSubjectMapping> findAllActive() {
                return List.of(new BudgetSubjectMapping(
                        UUID.randomUUID(), "M1001", "办公设备采购", "M1001",
                        "办公设备采购", approvalAmount, "2026", "供应商", "ACTIVE"));
            }
        };
        BudgetMaterialParser parser = new BudgetMaterialParser() {
            @Override
            public boolean supports(String fileName, String contentType) {
                return fileName.endsWith(".txt");
            }

            @Override
            public List<BudgetMaterialLine> parse(java.io.InputStream inputStream, String fileName) {
                return lines;
            }
        };
        BudgetProjectConfigRepository projectRepository = new BudgetProjectConfigRepository() {
            @Override
            public List<BudgetProjectConfig> findAllActive() {
                return projectConfigs;
            }
        };
        BudgetYearValidityConfigRepository yearRepository = new BudgetYearValidityConfigRepository() {
            @Override
            public List<BudgetYearValidityConfig> findAllActive() {
                return yearValidityConfigs;
            }
        };
        return new BudgetReviewService(
                new InMemoryBudgetReviewTaskRepository(),
                new InMemoryBudgetReviewItemRepository(),
                mappings,
                List.of(parser),
                logger,
                new InMemoryBudgetValidationConfirmLogRepository(),
                new BudgetCategoryRuleRepository() {},
                projectRepository,
                yearRepository,
                blacklistCheckPort,
                null,
                new InMemoryBudgetMaterialStorage());
    }

    private BudgetReviewService categoryService(
            BudgetMaterialLine line,
            BudgetSubjectMapping mapping,
            List<BudgetCategoryRule> rules) {
        BudgetSubjectMappingRepository mappings = new BudgetSubjectMappingRepository() {
            @Override
            public List<BudgetSubjectMapping> findBySystemSubjectCode(String code) {
                return List.of(mapping);
            }

            @Override
            public List<BudgetSubjectMapping> findAllActive() {
                return List.of(mapping);
            }
        };
        BudgetMaterialParser parser = new BudgetMaterialParser() {
            @Override
            public boolean supports(String fileName, String contentType) {
                return fileName.endsWith(".txt");
            }

            @Override
            public List<BudgetMaterialLine> parse(java.io.InputStream inputStream, String fileName) {
                return List.of(line);
            }
        };
        BudgetCategoryRuleRepository categoryRepository = new BudgetCategoryRuleRepository() {
            @Override
            public List<BudgetCategoryRule> findAllActive() {
                return rules;
            }
        };
        return new BudgetReviewService(
                new InMemoryBudgetReviewTaskRepository(),
                new InMemoryBudgetReviewItemRepository(),
                mappings,
                List.of(parser),
                logger,
                new InMemoryBudgetValidationConfirmLogRepository(),
                categoryRepository,
                new BudgetProjectConfigRepository() {},
                new BudgetYearValidityConfigRepository() {},
                command -> new com.fuquan.audit.blacklist.application.port.BlacklistCheckPort.CheckOutcome(
                        command.batchId(), command.supplierName(), command.supplierCode(),
                        false, List.of(), null),
                null,
                new InMemoryBudgetMaterialStorage());
    }

    private static BudgetCategoryRule categoryRule(
            String code,
            String name,
            String allowed,
            String forbidden,
            String suggestion) {
        return new BudgetCategoryRule(
                UUID.randomUUID(), code, name, allowed, forbidden, suggestion,
                "ACTIVE", java.time.Instant.now(), java.time.Instant.now());
    }

    private static BudgetMaterialLine line(
            String code, String name, String amount, String supplier, String project) {
        return line(2, code, name, amount, supplier, project);
    }

    private static BudgetMaterialLine line(
            int rowNumber,
            String code,
            String name,
            String amount,
            String supplier,
            String project) {
        return new BudgetMaterialLine(
                rowNumber, code, name, amount,
                BudgetTextNormalizer.amount(amount), supplier, project);
    }

    private static BudgetProjectConfig projectConfig(String projectCode, String departmentCode) {
        return new BudgetProjectConfig(
                UUID.randomUUID(), projectCode, projectCode + "项目",
                departmentCode, departmentCode, "ACTIVE", java.time.Instant.now(), java.time.Instant.now());
    }

    private static BudgetYearValidityConfig yearConfig(
            String subjectCode, int startYear, int endYear) {
        return new BudgetYearValidityConfig(
                UUID.randomUUID(), subjectCode, startYear, endYear,
                "ACTIVE", java.time.Instant.now(), java.time.Instant.now());
    }
}
