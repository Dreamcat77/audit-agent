package com.fuquan.audit.budget.application;

import java.util.List;

public record BudgetApprovalValidationPage(
        List<BudgetApprovalProgramView> items,
        int page,
        int size,
        long total) {
}
