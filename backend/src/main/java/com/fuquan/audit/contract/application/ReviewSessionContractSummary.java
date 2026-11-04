package com.fuquan.audit.contract.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 全局内审任务行中的合同轻量快照。 */
public record ReviewSessionContractSummary(
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
}
