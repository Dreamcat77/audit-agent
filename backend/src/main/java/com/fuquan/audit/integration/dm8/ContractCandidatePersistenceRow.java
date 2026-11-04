package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ContractCandidatePersistenceRow(
        String id, String projectId, String sourceContractFileId,
        String sourceParseResultId, String sourceRoleDecisionId, String sourceIdentityDecisionId,
        String sourceAuthoritySource,
        String sourceFactFingerprint, String contractNo,
        String contractName, String contractType, String partyA, String partyB,
        String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
        BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
        String promotionState, long versionNo, String promotedContractId,
        String promotedConfirmationId, String promotedBy,
        LocalDateTime promotedAt,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        Long supportAuthorityVersionNo, String supportAuthorityFingerprint) {
}
