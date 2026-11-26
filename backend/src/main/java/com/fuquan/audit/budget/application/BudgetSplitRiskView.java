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
        var records=assessment.records().stream().filter(r->BudgetAmountThresholdPolicy.activeRule(r.ruleCode())).toList();
        boolean filtered=records.size()!=assessment.records().size();
        int score=filtered?records.stream().mapToInt(r->r.riskScore()).max().orElse(0):assessment.riskScore();
        String level=filtered?(records.stream().anyMatch(r->"CRITICAL".equals(r.riskLevel()))?"CRITICAL":records.isEmpty()?"INFO":"WARNING"):assessment.riskLevel();
        long affected=records.stream().flatMap(r->r.procurementIds().stream()).distinct().count();
        int window=records.stream().mapToInt(r->r.procurementCount()).max().orElse(0);
        return new BudgetSplitRiskView(
                assessment.id(), assessment.procurementIds(), assessment.totalAmount(),level,score,
                filtered?percent(affected,assessment.procurementIds().size()):assessment.splitRatio(),
                filtered?percent(window,assessment.procurementIds().size()):assessment.timeConcentration(),assessment.supplierConcentration(),
                records.stream().map(record -> new RecordView(
                        record.ruleCode(), record.budgetSubject(), record.supplierName(),
                        record.totalAmount(), record.procurementCount(), record.riskScore(),
                        record.riskLevel(), record.windowStart(), record.windowEnd(),
                        record.procurementIds())).toList(),
                assessment.createdAt());
    }
    private static BigDecimal percent(long count,int total) {
        return total==0?BigDecimal.ZERO:BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total),2,java.math.RoundingMode.HALF_UP);
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
