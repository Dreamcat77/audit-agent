package com.fuquan.audit.budget.application;

import java.math.BigDecimal;

public record BudgetMaterialLine(
        int rowNumber,
        String subjectCode,
        String subjectName,
        String declaredAmountText,
        BigDecimal declaredAmount,
        String supplierName,
        String projectCode) {

    public BudgetMaterialLine(int rowNumber, String subjectName, BigDecimal declaredAmount) {
        this(rowNumber, null, subjectName,
                declaredAmount == null ? null : declaredAmount.toPlainString(),
                declaredAmount, null, null);
    }

    public BudgetMaterialLine(
            int rowNumber,
            String subjectCode,
            String subjectName,
            BigDecimal declaredAmount,
            String supplierName) {
        this(rowNumber, subjectCode, subjectName,
                declaredAmount == null ? null : declaredAmount.toPlainString(),
                declaredAmount, supplierName, null);
    }
}
