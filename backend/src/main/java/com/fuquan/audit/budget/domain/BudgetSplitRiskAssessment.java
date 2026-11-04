package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** F05 项目拆分风险评估事实，DM8 是唯一事实源。 */
public record BudgetSplitRiskAssessment(
        UUID id,
        List<UUID> procurementIds,
        BigDecimal totalAmount,
        String riskLevel,
        int riskScore,
        BigDecimal splitRatio,
        BigDecimal timeConcentration,
        BigDecimal supplierConcentration,
        List<BudgetSplitRiskRecord> records,
        String createdBy,
        Instant createdAt) {

    public BudgetSplitRiskAssessment {
        Objects.requireNonNull(id, "id must not be null");
        procurementIds = List.copyOf(procurementIds);
        records = List.copyOf(records);
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(splitRatio, "splitRatio must not be null");
        Objects.requireNonNull(timeConcentration, "timeConcentration must not be null");
        Objects.requireNonNull(supplierConcentration, "supplierConcentration must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!List.of("CRITICAL", "WARNING", "INFO").contains(riskLevel)) {
            throw new IllegalArgumentException("riskLevel is invalid");
        }
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
    }
}
