package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F01 预算审批 — 采购需求领域对象。
 * <p>
 * 对应 DM8 表 {@code PROCUREMENT_DEMAND}，承载 procurementId → budgetApprovalId 的查询链路。
 */
public record ProcurementDemand(
        UUID id,
        String procurementName,
        String budgetApprovalId,
        String subjectCode,
        String subjectName,
        BigDecimal amount,
        String supplierName,
        String status,
        Instant createdTime) {

    public ProcurementDemand {
        Objects.requireNonNull(id, "id must not be null");
        if (procurementName == null || procurementName.isBlank()) {
            throw new IllegalArgumentException("procurementName must not be blank");
        }
        Objects.requireNonNull(subjectCode, "subjectCode must not be null");
    }
}
