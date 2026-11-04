package com.fuquan.audit.budget.application;

import java.util.List;

public record BudgetApprovalEvaluation(
        List<String> requiredProcedureSteps,
        String requiredAuthorityStep,
        List<String> actualSteps,
        List<String> missingProcedureSteps,
        boolean procedureOrderValid,
        boolean authorityMatched,
        boolean passed,
        String resultSummary) {

    public BudgetApprovalEvaluation {
        requiredProcedureSteps = List.copyOf(requiredProcedureSteps);
        actualSteps = List.copyOf(actualSteps);
        missingProcedureSteps = List.copyOf(missingProcedureSteps);
    }
}
