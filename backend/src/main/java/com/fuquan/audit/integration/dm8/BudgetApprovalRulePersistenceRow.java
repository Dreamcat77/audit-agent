package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BudgetApprovalRulePersistenceRow(
        String id,
        String ruleSetCode,
        String ruleGroupCode,
        String matchMode,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minInclusive,
        Integer maxInclusive,
        String stepCode,
        String stepName,
        Integer stepOrder,
        String status,
        Long versionNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
