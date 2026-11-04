package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BudgetApprovalProgramView(
        UUID validationId,
        String ruleSetCode,
        UUID taskId,
        UUID itemId,
        Integer sourceRowNumber,
        BigDecimal declaredAmount,
        List<BudgetApprovalStep> requiredProcedureSteps,
        BudgetApprovalStep requiredAuthorityStep,
        List<BudgetApprovalStep> actualSteps,
        List<BudgetApprovalStep> missingProcedureSteps,
        boolean procedureOrderValid,
        boolean authorityMatched,
        boolean passed,
        String resultSummary,
        String actualSource,
        Instant createdAt) {
}
