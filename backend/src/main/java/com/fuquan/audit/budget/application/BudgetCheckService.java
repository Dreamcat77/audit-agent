package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetApprovalCheckRepository;
import com.fuquan.audit.budget.application.port.BudgetCheckPort;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.ProcurementDemandRepository;
import com.fuquan.audit.budget.domain.BudgetCheckResult;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * F01 预算审批校验 — 应用服务。
 * <p>
 * 编排科目编码、预算金额、供应商名称、科目名称四项校验，
 * 并将结果持久化到 {@code BUDGET_APPROVAL_CHECK} 表。
 */
public class BudgetCheckService implements BudgetCheckPort {

    private final ProcurementDemandRepository procurementDemandRepository;
    private final BudgetSubjectMappingRepository subjectMappingRepository;
    private final BudgetApprovalCheckRepository approvalCheckRepository;
    private final FlowDebugLogger flowDebugLogger;

    public BudgetCheckService(
            ProcurementDemandRepository procurementDemandRepository,
            BudgetSubjectMappingRepository subjectMappingRepository,
            BudgetApprovalCheckRepository approvalCheckRepository,
            FlowDebugLogger flowDebugLogger) {
        this.procurementDemandRepository = procurementDemandRepository;
        this.subjectMappingRepository = subjectMappingRepository;
        this.approvalCheckRepository = approvalCheckRepository;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public List<BudgetCheckResult> checkBudgetApproval(UUID procurementId) {
        Objects.requireNonNull(procurementId, "procurementId must not be null");
        long startedAt = System.nanoTime();
        String pid = procurementId.toString();
        flowDebugLogger.log("BUDGET_CHECK_START", "BUDGET", "APPLICATION", "checkBudgetApproval",
                Map.of("procurementId", pid));

        // 1. 查询采购需求
        ProcurementDemand procurement = procurementDemandRepository.findById(procurementId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND,
                        "procurement demand not found: " + pid));

        if (!isNotBlank(procurement.subjectCode())) {
            List<BudgetCheckResult> results = failedChecksWithoutMapping(pid, procurement);
            approvalCheckRepository.saveAll(results);
            logCompletion(pid, results, startedAt);
            return results;
        }

        // 2. 查询预算科目映射
        List<BudgetSubjectMapping> mappings =
                subjectMappingRepository.findBySystemSubjectCode(procurement.subjectCode());
        if (mappings.isEmpty()) {
            throw new BusinessException(
                    BusinessErrorCode.NOT_FOUND,
                    "budget subject mapping not found for code: " + procurement.subjectCode());
        }
        if (mappings.size() > 1) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT,
                    "multiple active budget subject mappings found for code: "
                            + procurement.subjectCode());
        }
        BudgetSubjectMapping mapping = mappings.get(0);

        flowDebugLogger.log("BUDGET_MAPPING_RESOLVED", "BUDGET", "APPLICATION", "mappingResolved",
                Map.of("systemCode", procurement.subjectCode(),
                        "budgetCode", mapping.budgetSubjectCode()));

        // 3. 执行多维度校验
        List<BudgetCheckResult> results = new ArrayList<>();

        // 3.1 科目编码比对
        results.add(checkSubjectCode(pid, procurement, mapping));

        // 3.2 预算金额比对
        results.add(checkBudgetAmount(pid, procurement, mapping));

        // 3.3 供应商名称比对
        results.add(checkSupplierName(pid, procurement, mapping));

        // 3.4 科目名称比对
        results.add(checkSubjectName(pid, procurement, mapping));

        // 4. 持久化校验结果
        approvalCheckRepository.saveAll(results);

        logCompletion(pid, results, startedAt);
        return results;
    }

    // ---- 校验维度实现 ----

    private BudgetCheckResult checkSubjectCode(
            String pid, ProcurementDemand procurement, BudgetSubjectMapping mapping) {
        boolean pass = trimmedEquals(mapping.budgetSubjectCode(), procurement.subjectCode());
        return pass
                ? BudgetCheckResult.pass(pid, "SUBJECT_CODE", procurement.subjectCode(),
                        mapping.budgetSubjectCode(), procurement.subjectCode(),
                        "校验通过：科目一致")
                : BudgetCheckResult.fail(pid, "SUBJECT_CODE", procurement.subjectCode(),
                        mapping.budgetSubjectCode(), procurement.subjectCode(),
                        "科目不一致：预算=" + mapping.budgetSubjectCode()
                                + ", 实际=" + procurement.subjectCode());
    }

    private BudgetCheckResult checkBudgetAmount(
            String pid, ProcurementDemand procurement, BudgetSubjectMapping mapping) {
        boolean pass = amountEquals(mapping.budgetAmount(), procurement.amount());
        String expected = toAmountText(mapping.budgetAmount());
        String actual = toAmountText(procurement.amount());
        return pass
                ? BudgetCheckResult.pass(pid, "BUDGET_AMOUNT", null,
                        expected,
                        actual,
                        "校验通过：金额一致")
                : BudgetCheckResult.fail(pid, "BUDGET_AMOUNT", null,
                        expected,
                        actual,
                        "金额不一致：预算=" + display(expected)
                                + ", 实际=" + display(actual));
    }

    private BudgetCheckResult checkSupplierName(
            String pid, ProcurementDemand procurement, BudgetSubjectMapping mapping) {
        boolean pass = trimmedEquals(mapping.supplierName(), procurement.supplierName());
        return pass
                ? BudgetCheckResult.pass(pid, "SUPPLIER_NAME", null,
                        mapping.supplierName(), procurement.supplierName(),
                        "校验通过：供应商一致")
                : BudgetCheckResult.fail(pid, "SUPPLIER_NAME", null,
                        mapping.supplierName(), procurement.supplierName(),
                        "供应商不一致：预算=" + mapping.supplierName()
                                + ", 实际=" + procurement.supplierName());
    }

    private BudgetCheckResult checkSubjectName(
            String pid, ProcurementDemand procurement, BudgetSubjectMapping mapping) {
        boolean pass = trimmedEquals(mapping.budgetSubjectName(), procurement.subjectName());
        return pass
                ? BudgetCheckResult.pass(pid, "SUBJECT_NAME", null,
                        mapping.budgetSubjectName(), procurement.subjectName(),
                        "校验通过：科目名称一致")
                : BudgetCheckResult.fail(pid, "SUBJECT_NAME", null,
                        mapping.budgetSubjectName(), procurement.subjectName(),
                        "科目名称不一致：预算=" + mapping.budgetSubjectName()
                                + ", 实际=" + procurement.subjectName());
    }

    // ---- 工具方法 ----

    private static boolean trimmedEquals(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.trim().equals(b.trim());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static List<BudgetCheckResult> failedChecksWithoutMapping(
            String pid, ProcurementDemand procurement) {
        String detail = "预算科目映射无法确定：实际科目编码为空";
        return List.of(
                BudgetCheckResult.fail(
                        pid, "SUBJECT_CODE", procurement.subjectCode(), null,
                        procurement.subjectCode(), detail),
                BudgetCheckResult.fail(
                        pid, "BUDGET_AMOUNT", null, null,
                        toAmountText(procurement.amount()), detail),
                BudgetCheckResult.fail(
                        pid, "SUPPLIER_NAME", null, null,
                        procurement.supplierName(), detail),
                BudgetCheckResult.fail(
                        pid, "SUBJECT_NAME", null, null,
                        procurement.subjectName(), detail));
    }

    private void logCompletion(String pid, List<BudgetCheckResult> results, long startedAt) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        long passedCount = results.stream().filter(BudgetCheckResult::passed).count();
        flowDebugLogger.log("BUDGET_CHECK_COMPLETE", "BUDGET", "APPLICATION", "checkComplete",
                Map.of("procurementId", pid, "total", results.size(),
                        "passed", passedCount, "durationMs", durationMs));
    }

    private static boolean amountEquals(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static String toAmountText(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static String display(String value) {
        return value == null ? "<空>" : value;
    }
}
