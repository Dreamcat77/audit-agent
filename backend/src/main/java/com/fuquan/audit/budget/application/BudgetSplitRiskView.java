package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.domain.BudgetSplitRiskAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BudgetSplitRiskView(
        UUID assessmentId,
        List<UUID> procurementIds,
        BigDecimal totalAmount,
        String riskLevel,
        int riskScore,
        BigDecimal splitRatio,
        BigDecimal timeConcentration,
        BigDecimal supplierConcentration,
        List<RecordView> records,
        Instant createdAt) {

    public static BudgetSplitRiskView from(BudgetSplitRiskAssessment assessment) {
        return new BudgetSplitRiskView(
                assessment.id(), assessment.procurementIds(), assessment.totalAmount(),
                assessment.riskLevel(), assessment.riskScore(), assessment.splitRatio(),
                assessment.timeConcentration(), assessment.supplierConcentration(),
                assessment.records().stream().map(record -> new RecordView(
                        record.ruleCode(), record.budgetSubject(), record.supplierName(),
                        record.totalAmount(), record.procurementCount(), record.riskScore(),
                        record.riskLevel(), record.windowStart(), record.windowEnd(),
                        record.procurementIds())).toList(),
                assessment.createdAt());
    }

    public record RecordView(
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
    }
}
