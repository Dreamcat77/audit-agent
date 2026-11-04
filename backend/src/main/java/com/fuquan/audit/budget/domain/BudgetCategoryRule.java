package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.UUID;

public record BudgetCategoryRule(
        UUID id,
        String categoryCode,
        String categoryName,
        String allowedKeywords,
        String forbiddenKeywords,
        String suggestCategory,
        String status,
        Instant createdTime,
        Instant updatedTime) {
}
