package com.fuquan.audit.contract.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 合同目录使用的轻量投影，不加载分项或中标依据。 */
public record ContractSummary(
        UUID id,
        String contractNo,
        String contractName,
        String contractType,
        String partyA,
        String partyB,
        String partyBCreditCode,
        BigDecimal contractAmount,
        String amountInWords,
        String status,
        long versionNo,
        Instant createdAt,
        Instant updatedAt) {
    public ContractSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contractAmount, "contractAmount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
