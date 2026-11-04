package com.fuquan.audit.budget.domain;

import java.util.Objects;

public record BudgetApprovalStep(String code, String displayName) {

    public BudgetApprovalStep {
        code = requireText(code, "code");
        displayName = requireText(displayName, "displayName");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
