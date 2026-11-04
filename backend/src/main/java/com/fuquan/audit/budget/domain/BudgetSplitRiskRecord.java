package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** F05 一组疑似化整为零记录的持久化快照。 */
public record BudgetSplitRiskRecord(
        String ruleCode,
        String budgetSubject,
        String supplierName,
        BigDecimal totalAmount,
        int procurementCount,
        int riskScore,
        String riskLevel,
        Instant windowStart,
        Instant windowEnd,
        List<UUID> procurementIds) {

    public BudgetSplitRiskRecord {
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(budgetSubject, "budgetSubject must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        procurementIds = List.copyOf(procurementIds);
        if (procurementCount != procurementIds.size() || procurementCount < 2) {
            throw new IllegalArgumentException("procurementCount does not match procurementIds");
        }
        if (riskScore < 0 || riskScore > 100 || !List.of("CRITICAL", "WARNING").contains(riskLevel)) {
            throw new IllegalArgumentException("risk result is invalid");
        }
    }
}
