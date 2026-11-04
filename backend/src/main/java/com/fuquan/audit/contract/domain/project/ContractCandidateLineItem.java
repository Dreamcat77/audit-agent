package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ContractCandidateLineItem(
        UUID id, UUID projectId, UUID candidateId, String itemKey, String itemName,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal declaredAmount, int sortNo) {
    public ContractCandidateLineItem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(candidateId);
        if (sortNo < 0) {
            throw new IllegalArgumentException("sortNo must be non-negative");
        }
    }
}
