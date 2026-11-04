package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F01 科目预算年度有效期配置。
 */
public record BudgetYearValidityConfig(
        UUID id,
        String subjectCode,
        int startYear,
        int endYear,
        String status,
        Instant createdTime,
        Instant updatedTime) {

    public BudgetYearValidityConfig {
        Objects.requireNonNull(id, "id must not be null");
        if (subjectCode == null || subjectCode.isBlank()) {
            throw new IllegalArgumentException("subjectCode must not be blank");
        }
        if (startYear > endYear) {
            throw new IllegalArgumentException("startYear must not be greater than endYear");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }
}
