package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BudgetApprovalValidationPersistenceRow(
        String id,
        String ruleSetCode,
        String sourceTaskId,
        String sourceItemId,
        Integer sourceRowNumber,
        BigDecimal declaredAmount,
        String actualSource,
        String requiredProcedureSteps,
        String requiredAuthorityStep,
        String actualSteps,
        String missingProcedureSteps,
        Integer procedureOrderValid,
        Integer authorityMatched,
        Integer passed,
        String resultSummary,
        String createdBy,
        LocalDateTime createdAt) {
}
