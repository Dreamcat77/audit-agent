package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * F01 预算审批 — 预算科目映射领域对象。
 * <p>
 * 承载系统科目编码到预算科目编码的映射关系，用于科目比对和金额校验。
 */
public record BudgetSubjectMapping(
        UUID id,
        String systemSubjectCode,
        String systemSubjectName,
        String budgetSubjectCode,
        String budgetSubjectName,
        BigDecimal budgetAmount,
        String budgetYear,
        String supplierName,
        String status) {

    public BudgetSubjectMapping {
        Objects.requireNonNull(id, "id must not be null");
        if (systemSubjectCode == null || systemSubjectCode.isBlank()) {
            throw new IllegalArgumentException("systemSubjectCode must not be blank");
        }
        if (budgetSubjectCode == null || budgetSubjectCode.isBlank()) {
            throw new IllegalArgumentException("budgetSubjectCode must not be blank");
        }
    }
}
