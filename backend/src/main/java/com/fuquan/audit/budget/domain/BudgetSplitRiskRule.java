package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** F05 项目拆分风险的可配置确定性规则。 */
public record BudgetSplitRiskRule(
        String ruleCode,
        String ruleName,
        int windowDays,
        BigDecimal individualAmountLimit,
        BigDecimal aggregateAmountThreshold,
        int minimumRecordCount,
        int warningScore,
        int criticalScore,
        int versionNo) {

    public BudgetSplitRiskRule {
        requireText(ruleCode, "ruleCode");
        requireText(ruleName, "ruleName");
        if (windowDays < 1 || minimumRecordCount < 2) {
            throw new IllegalArgumentException("windowDays and minimumRecordCount are invalid");
        }
        Objects.requireNonNull(individualAmountLimit, "individualAmountLimit must not be null");
        Objects.requireNonNull(aggregateAmountThreshold, "aggregateAmountThreshold must not be null");
        if (individualAmountLimit.signum() <= 0 || aggregateAmountThreshold.signum() <= 0) {
            throw new IllegalArgumentException("amount thresholds must be positive");
        }
        if (warningScore < 0 || criticalScore > 100 || warningScore >= criticalScore) {
            throw new IllegalArgumentException("risk score thresholds are invalid");
        }
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
