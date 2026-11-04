package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetSplitRiskEvaluatorTest {

    private final BudgetSplitRiskEvaluator evaluator = new BudgetSplitRiskEvaluator();
    private final BudgetSplitRiskRule rule = new BudgetSplitRiskRule(
            "F05_50K", "5万元审批阈值拆分", 30,
            new BigDecimal("50000.00"), new BigDecimal("50000.00"),
            2, 60, 85, 1);

    @Test
    void detectsTwoSubThresholdRecordsForSameSubjectAndSupplier() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var result = evaluator.evaluate(List.of(
                demand("办公设备采购", "30000.00", "M1001", "供应商A", now),
                demand("办公设备采购", "30000.00", "M1001", "供应商A", now.plus(10, ChronoUnit.DAYS))),
                List.of(rule));

        assertThat(result.riskLevel()).isIn("WARNING", "CRITICAL");
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).totalAmount()).isEqualByComparingTo("60000.00");
        assertThat(result.splitRatio()).isEqualByComparingTo("100.00");
    }

    @Test
    void doesNotCombineDifferentSubjectsOrProjects() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var result = evaluator.evaluate(List.of(
                demand("办公设备采购", "30000.00", "M1001", "供应商A", now),
                demand("办公设备采购", "30000.00", "M2001", "供应商A", now),
                demand("办公家具采购", "30000.00", "M1001", "供应商A", now)),
                List.of(rule));

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.riskScore()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void exactIndividualThresholdIsNotTreatedAsSmallAmountSplit() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var result = evaluator.evaluate(List.of(
                demand("办公设备采购", "50000.00", "M1001", "供应商A", now),
                demand("办公设备采购", "10000.00", "M1001", "供应商A", now)),
                List.of(rule));

        assertThat(result.riskLevel()).isEqualTo("INFO");
    }

    @Test
    void recordsOutsideThirtyDayWindowDoNotTrigger() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var result = evaluator.evaluate(List.of(
                demand("办公设备采购", "30000.00", "M1001", "供应商A", now),
                demand("办公设备采购", "30000.00", "M1001", "供应商A", now.plus(31, ChronoUnit.DAYS))),
                List.of(rule));

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.records()).isEmpty();
    }

    @Test
    void detectsSameProjectWithoutInventingSupplierData() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var result = evaluator.evaluate(List.of(
                demand("办公 设备采购", "30000.00", "M1001", null, now),
                demand("办公设备采购", "30000.00", "M1001", null, now.plus(2, ChronoUnit.DAYS))),
                List.of(rule));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).supplierName()).isNull();
    }

    @Test
    void rejectsIncompleteRecordInsteadOfReturningHealthyZero() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        ProcurementDemand incomplete = new ProcurementDemand(
                UUID.randomUUID(), "办公设备采购", "BA-001", "M1001",
                "办公设备", null, null, "APPROVED", now);

        assertThatThrownBy(() -> evaluator.evaluate(List.of(
                demand("办公设备采购", "30000.00", "M1001", null, now), incomplete),
                List.of(rule)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少有效金额");
    }

    private static ProcurementDemand demand(
            String projectName, String amount, String subjectCode, String supplier, Instant createdTime) {
        return new ProcurementDemand(UUID.randomUUID(), projectName, "BA-001", subjectCode,
                "办公设备", new BigDecimal(amount), supplier, "APPROVED", createdTime);
    }
}
