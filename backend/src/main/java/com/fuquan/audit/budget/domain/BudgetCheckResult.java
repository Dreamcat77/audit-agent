package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F01 预算审批 — 单条校验结果领域对象。
 * <p>
 * 每次校验产生多条 {@code BudgetCheckResult}，分别对应科目编码、金额、供应商名称等校验维度。
 */
public record BudgetCheckResult(
        UUID id,
        String procurementId,
        String checkType,
        String subjectCode,
        String expectedValue,
        String actualValue,
        boolean passed,
        String errorDetail,
        Instant checkTime) {

    public BudgetCheckResult {
        Objects.requireNonNull(id, "id must not be null");
        if (procurementId == null || procurementId.isBlank()) {
            throw new IllegalArgumentException("procurementId must not be blank");
        }
        if (checkType == null || checkType.isBlank()) {
            throw new IllegalArgumentException("checkType must not be blank");
        }
        if (errorDetail == null || errorDetail.isBlank()) {
            throw new IllegalArgumentException("errorDetail must not be blank");
        }
        Objects.requireNonNull(checkTime, "checkTime must not be null");
    }

    public static BudgetCheckResult pass(
            String procurementId, String checkType, String subjectCode,
            String expectedValue, String actualValue, String detail) {
        return new BudgetCheckResult(
                UUID.randomUUID(), procurementId, checkType, subjectCode,
                expectedValue, actualValue, true, detail, Instant.now());
    }

    public static BudgetCheckResult fail(
            String procurementId, String checkType, String subjectCode,
            String expectedValue, String actualValue, String detail) {
        return new BudgetCheckResult(
                UUID.randomUUID(), procurementId, checkType, subjectCode,
                expectedValue, actualValue, false, detail, Instant.now());
    }
}
