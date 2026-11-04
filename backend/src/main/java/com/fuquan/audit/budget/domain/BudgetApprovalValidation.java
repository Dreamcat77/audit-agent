package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BudgetApprovalValidation(
        UUID id,
        String ruleSetCode,
        UUID sourceTaskId,
        UUID sourceItemId,
        Integer sourceRowNumber,
        BigDecimal declaredAmount,
        String actualSource,
        List<String> requiredProcedureSteps,
        String requiredAuthorityStep,
        List<String> actualSteps,
        List<String> missingProcedureSteps,
        boolean procedureOrderValid,
        boolean authorityMatched,
        boolean passed,
        String resultSummary,
        String createdBy,
        Instant createdAt) {

    public BudgetApprovalValidation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceTaskId, "sourceTaskId must not be null");
        Objects.requireNonNull(sourceItemId, "sourceItemId must not be null");
        Objects.requireNonNull(declaredAmount, "declaredAmount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        requiredProcedureSteps = List.copyOf(requiredProcedureSteps);
        actualSteps = List.copyOf(actualSteps);
        missingProcedureSteps = List.copyOf(missingProcedureSteps);
    }
}
