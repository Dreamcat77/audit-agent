package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 可配置的预算审批程序规则。PM_DEMO_20260828 是演示规则集，待甲方审批权限矩阵最终确认。
 */
public record BudgetApprovalRule(
        UUID id,
        String ruleSetCode,
        BudgetApprovalRuleGroup ruleGroup,
        String matchMode,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean minInclusive,
        boolean maxInclusive,
        BudgetApprovalStep step,
        int stepOrder,
        String status,
        long versionNo) {

    public BudgetApprovalRule {
        Objects.requireNonNull(id, "id must not be null");
        if (ruleSetCode == null || ruleSetCode.isBlank()) {
            throw new IllegalArgumentException("ruleSetCode must not be blank");
        }
        Objects.requireNonNull(ruleGroup, "ruleGroup must not be null");
        if (matchMode == null || matchMode.isBlank()) {
            throw new IllegalArgumentException("matchMode must not be blank");
        }
        if (ruleGroup == BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT
                && !"CUMULATIVE_MIN".equals(matchMode)) {
            throw new IllegalArgumentException("procedure rule matchMode must be CUMULATIVE_MIN");
        }
        if (ruleGroup == BudgetApprovalRuleGroup.AUTHORITY_LEVEL
                && !"RANGE".equals(matchMode)) {
            throw new IllegalArgumentException("authority rule matchMode must be RANGE");
        }
        if ("CUMULATIVE_MIN".equals(matchMode)
                && (minAmount == null || maxAmount != null)) {
            throw new IllegalArgumentException(
                    "CUMULATIVE_MIN requires a minimum and no maximum");
        }
        Objects.requireNonNull(step, "step must not be null");
        if (stepOrder < 0) {
            throw new IllegalArgumentException("stepOrder must not be negative");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new IllegalArgumentException("minAmount must not exceed maxAmount");
        }
    }

    public boolean matches(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        boolean aboveMinimum = minAmount == null
                || amount.compareTo(minAmount) > 0
                || (minInclusive && amount.compareTo(minAmount) == 0);
        boolean belowMaximum = maxAmount == null
                || amount.compareTo(maxAmount) < 0
                || (maxInclusive && amount.compareTo(maxAmount) == 0);
        return aboveMinimum && belowMaximum;
    }
}
