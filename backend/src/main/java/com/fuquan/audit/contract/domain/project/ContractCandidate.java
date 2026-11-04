package com.fuquan.audit.contract.domain.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

public record ContractCandidate(
        UUID id,
        UUID projectId,
        UUID sourceContractFileId,
        UUID sourceParseResultId,
        UUID sourceRoleDecisionId,
        UUID sourceIdentityDecisionId,
        ContractCandidateAuthoritySource sourceAuthoritySource,
        String sourceFactFingerprint,
        String contractNo,
        String contractName,
        String contractType,
        String partyA,
        String partyB,
        String partyBCreditCode,
        BigDecimal contractAmount,
        String amountInWords,
        BigDecimal awardAmount,
        String awardSupplier,
        String awardSupplierCreditCode,
        List<ContractCandidateLineItem> lineItems,
        CandidatePromotionState promotionState,
        long versionNo,
        UUID promotedContractId,
        UUID promotedConfirmationId,
        String promotedBy,
        Instant promotedAt,
        Instant createdAt,
        Instant updatedAt,
        @JsonIgnore Long supportAuthorityVersionNo,
        @JsonIgnore String supportAuthorityFingerprint) {

    public ContractCandidate {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceContractFileId, "sourceContractFileId must not be null");
        Objects.requireNonNull(promotionState, "promotionState must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems must not be null"));
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must not be negative");
        }
        boolean noSupportAuthority = supportAuthorityVersionNo == null && supportAuthorityFingerprint == null;
        if (!noSupportAuthority && (supportAuthorityVersionNo == null || supportAuthorityFingerprint == null)) {
            throw new IllegalArgumentException("candidate support authority metadata is incomplete");
        }
        if (!noSupportAuthority && supportAuthorityVersionNo <= 0) {
            throw new IllegalArgumentException("supportAuthorityVersionNo must be positive");
        }
        if (!noSupportAuthority && !supportAuthorityFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("supportAuthorityFingerprint must be a lowercase SHA-256 hash");
        }
        if (promotionState == CandidatePromotionState.DRAFT
                && (promotedContractId != null || promotedConfirmationId != null
                || promotedBy != null || promotedAt != null)) {
            throw new IllegalArgumentException("draft candidate must not contain promotion facts");
        }
        if (promotionState == CandidatePromotionState.PROMOTED
                && (promotedContractId == null || promotedBy == null || promotedBy.isBlank() || promotedAt == null)) {
            throw new IllegalArgumentException("promoted candidate requires complete promotion facts");
        }
        boolean noAuthority = sourceParseResultId == null && sourceRoleDecisionId == null
                && sourceIdentityDecisionId == null
                && sourceAuthoritySource == null && sourceFactFingerprint == null;
        boolean confirmedAuthority = sourceParseResultId != null && sourceRoleDecisionId != null
                && (sourceAuthoritySource == ContractCandidateAuthoritySource.HUMAN_CONFIRMATION
                    || sourceAuthoritySource == ContractCandidateAuthoritySource.SYSTEM_AUTO)
                && sourceFactFingerprint != null && sourceFactFingerprint.matches("[0-9a-f]{64}");
        boolean explicitLegacy = sourceParseResultId != null && sourceRoleDecisionId == null
                && sourceIdentityDecisionId == null
                && sourceAuthoritySource == ContractCandidateAuthoritySource.LEGACY_COMPAT
                && sourceFactFingerprint == null;
        if (!noAuthority && !confirmedAuthority && !explicitLegacy) {
            throw new IllegalArgumentException("candidate source authority is incomplete");
        }
    }

    /** Old PROMOTED rows legitimately have no confirmation provenance. */
    public String promotionProvenanceSource() {
        return promotionState == CandidatePromotionState.DRAFT ? null
                : promotedConfirmationId == null ? "LEGACY" : "DURABLE_CONFIRMATION";
    }

    /** Historical full constructor before support authority metadata existed. */
    public ContractCandidate(UUID id, UUID projectId, UUID sourceContractFileId,
            UUID sourceParseResultId, UUID sourceRoleDecisionId, UUID sourceIdentityDecisionId,
            ContractCandidateAuthoritySource sourceAuthoritySource, String sourceFactFingerprint,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, CandidatePromotionState promotionState,
            long versionNo, UUID promotedContractId, UUID promotedConfirmationId, String promotedBy,
            Instant promotedAt, Instant createdAt, Instant updatedAt) {
        this(id, projectId, sourceContractFileId, sourceParseResultId, sourceRoleDecisionId,
                sourceIdentityDecisionId, sourceAuthoritySource, sourceFactFingerprint, contractNo,
                contractName, contractType, partyA, partyB, partyBCreditCode, contractAmount,
                amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode, lineItems,
                promotionState, versionNo, promotedContractId, promotedConfirmationId, promotedBy,
                promotedAt, createdAt, updatedAt, null, null);
    }

    /** Historical full constructor for pre-Phase1E rows and callers. */
    public ContractCandidate(UUID id, UUID projectId, UUID sourceContractFileId,
            UUID sourceParseResultId, UUID sourceRoleDecisionId, UUID sourceIdentityDecisionId,
            ContractCandidateAuthoritySource sourceAuthoritySource, String sourceFactFingerprint,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, CandidatePromotionState promotionState,
            long versionNo, UUID promotedContractId, String promotedBy, Instant promotedAt,
            Instant createdAt, Instant updatedAt) {
        this(id, projectId, sourceContractFileId, sourceParseResultId, sourceRoleDecisionId,
                sourceIdentityDecisionId, sourceAuthoritySource, sourceFactFingerprint, contractNo,
                contractName, contractType, partyA, partyB, partyBCreditCode, contractAmount,
                amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode, lineItems,
                promotionState, versionNo, promotedContractId, null, promotedBy, promotedAt,
                createdAt, updatedAt);
    }

    /** Historical Phase1C constructor: NULL identity provenance is a real, readable old fact. */
    public ContractCandidate(UUID id, UUID projectId, UUID sourceContractFileId,
            UUID sourceParseResultId, UUID sourceRoleDecisionId,
            ContractCandidateAuthoritySource sourceAuthoritySource, String sourceFactFingerprint,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, CandidatePromotionState promotionState,
            long versionNo, UUID promotedContractId, String promotedBy, Instant promotedAt,
            Instant createdAt, Instant updatedAt) {
        this(id, projectId, sourceContractFileId, sourceParseResultId, sourceRoleDecisionId, null,
                sourceAuthoritySource, sourceFactFingerprint, contractNo, contractName, contractType,
                partyA, partyB, partyBCreditCode, contractAmount, amountInWords, awardAmount,
                awardSupplier, awardSupplierCreditCode, lineItems, promotionState, versionNo,
                promotedContractId, null, promotedBy, promotedAt, createdAt, updatedAt);
    }

    public ContractCandidate(UUID id, UUID projectId, UUID sourceContractFileId,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, CandidatePromotionState promotionState,
            long versionNo, UUID promotedContractId, String promotedBy, Instant promotedAt,
            Instant createdAt, Instant updatedAt) {
        this(id, projectId, sourceContractFileId, null, null, null, null, null, contractNo, contractName,
                contractType, partyA, partyB, partyBCreditCode, contractAmount, amountInWords,
                awardAmount, awardSupplier, awardSupplierCreditCode, lineItems, promotionState,
                versionNo, promotedContractId, null, promotedBy, promotedAt, createdAt, updatedAt);
    }

    public static ContractCandidate draft(
            UUID id, UUID projectId, UUID sourceFileId, String contractNo, String contractName,
            String contractType, String partyA, String partyB, String partyBCreditCode,
            BigDecimal contractAmount, String amountInWords, BigDecimal awardAmount,
            String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, Instant now) {
        return new ContractCandidate(id, projectId, sourceFileId, null, null, null, null, null,
                contractNo, contractName, contractType,
                partyA, partyB, partyBCreditCode, contractAmount, amountInWords, awardAmount,
                awardSupplier, awardSupplierCreditCode, lineItems, CandidatePromotionState.DRAFT,
                0, null, null, null, null, now, now);
    }

    public static ContractCandidate draftWithAuthority(UUID id, UUID projectId, UUID sourceFileId,
            UUID sourceParseResultId, UUID sourceRoleDecisionId,
            ContractCandidateAuthoritySource sourceAuthoritySource, String sourceFactFingerprint,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, Instant now) {
        return draftWithAuthority(id, projectId, sourceFileId, sourceParseResultId,
                sourceRoleDecisionId, null, sourceAuthoritySource, sourceFactFingerprint,
                contractNo, contractName, contractType, partyA, partyB, partyBCreditCode,
                contractAmount, amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode,
                lineItems, now);
    }

    public static ContractCandidate draftWithAuthority(UUID id, UUID projectId, UUID sourceFileId,
            UUID sourceParseResultId, UUID sourceRoleDecisionId, UUID sourceIdentityDecisionId,
            ContractCandidateAuthoritySource sourceAuthoritySource, String sourceFactFingerprint,
            String contractNo, String contractName, String contractType, String partyA, String partyB,
            String partyBCreditCode, BigDecimal contractAmount, String amountInWords,
            BigDecimal awardAmount, String awardSupplier, String awardSupplierCreditCode,
            List<ContractCandidateLineItem> lineItems, Instant now) {
        return new ContractCandidate(id, projectId, sourceFileId, sourceParseResultId,
                sourceRoleDecisionId, sourceIdentityDecisionId, sourceAuthoritySource, sourceFactFingerprint, contractNo,
                contractName, contractType, partyA, partyB, partyBCreditCode, contractAmount,
                amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode, lineItems,
                CandidatePromotionState.DRAFT, 0, null, null, null, null, now, now);
    }

    public ContractCandidate reauthorize(UUID parseResultId, UUID roleDecisionId,
            String factFingerprint, Instant now) {
        return reauthorize(parseResultId, roleDecisionId, sourceIdentityDecisionId, factFingerprint, now);
    }

    public ContractCandidate reauthorize(UUID parseResultId, UUID roleDecisionId,
            UUID identityDecisionId, String factFingerprint, Instant now) {
        return reauthorize(parseResultId, roleDecisionId, identityDecisionId,
                ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, factFingerprint, now);
    }

    public ContractCandidate reauthorize(UUID parseResultId, UUID roleDecisionId,
            UUID identityDecisionId, ContractCandidateAuthoritySource authoritySource,
            String factFingerprint, Instant now) {
        if (promotionState != CandidatePromotionState.DRAFT) {
            throw new IllegalStateException("promoted candidate cannot be reauthorized");
        }
        if (Objects.equals(sourceParseResultId, parseResultId)
                && Objects.equals(sourceRoleDecisionId, roleDecisionId)
                && Objects.equals(sourceIdentityDecisionId, identityDecisionId)
                && sourceAuthoritySource == authoritySource
                && Objects.equals(sourceFactFingerprint, factFingerprint)) return this;
        return new ContractCandidate(id, projectId, sourceContractFileId, parseResultId, roleDecisionId,
                identityDecisionId,
                authoritySource, factFingerprint, contractNo,
                contractName, contractType, partyA, partyB, partyBCreditCode, contractAmount,
                amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode, lineItems,
                promotionState, versionNo + 1, null, null, null, null, createdAt, now,
                supportAuthorityVersionNo, supportAuthorityFingerprint);

    }

    public ContractCandidate confirmed(Instant now) {
        if (promotionState != CandidatePromotionState.DRAFT) {
            throw new IllegalStateException("promoted candidate cannot be confirmed");
        }
        return new ContractCandidate(id, projectId, sourceContractFileId, sourceParseResultId,
                sourceRoleDecisionId, sourceIdentityDecisionId, sourceAuthoritySource, sourceFactFingerprint,
                contractNo, contractName, contractType, partyA, partyB, partyBCreditCode,
                contractAmount, amountInWords, awardAmount, awardSupplier, awardSupplierCreditCode,
                lineItems, promotionState, versionNo + 1, null, null, null, null, createdAt, now,
                supportAuthorityVersionNo, supportAuthorityFingerprint);
    }

    public ContractCandidate promote(UUID contractId, String actor, Instant now) {
        return promote(contractId, null, actor, now);
    }

    public ContractCandidate promote(UUID contractId, UUID confirmationId, String actor, Instant now) {
        if (promotionState != CandidatePromotionState.DRAFT) {
            throw new IllegalStateException("candidate is already promoted");
        }
        return new ContractCandidate(id, projectId, sourceContractFileId, sourceParseResultId,
                sourceRoleDecisionId, sourceIdentityDecisionId, sourceAuthoritySource, sourceFactFingerprint, contractNo, contractName,
                contractType, partyA, partyB, partyBCreditCode, contractAmount, amountInWords,
                awardAmount, awardSupplier, awardSupplierCreditCode, lineItems,
                CandidatePromotionState.PROMOTED, versionNo + 1, contractId, confirmationId,
                actor, now, createdAt, now, supportAuthorityVersionNo, supportAuthorityFingerprint);
    }
}
