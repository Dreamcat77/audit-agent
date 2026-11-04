package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F01 项目归属配置。
 */
public record BudgetProjectConfig(
        UUID id,
        String projectCode,
        String projectName,
        String departmentCode,
        String departmentName,
        String status,
        Instant createdTime,
        Instant updatedTime) {

    public BudgetProjectConfig {
        Objects.requireNonNull(id, "id must not be null");
        if (projectCode == null || projectCode.isBlank()) {
            throw new IllegalArgumentException("projectCode must not be blank");
        }
        if (departmentCode == null || departmentCode.isBlank()) {
            throw new IllegalArgumentException("departmentCode must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }
}
