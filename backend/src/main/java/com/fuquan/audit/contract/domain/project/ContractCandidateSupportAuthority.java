package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable current-cohort authority accepted by one Candidate for one award notice file. */
public record ContractCandidateSupportAuthority(
        UUID supportAuthorityId,
        UUID projectId,
        UUID candidateId,
        UUID sourceFileId,
        String fileSha256,
        UUID parseResultId,
        int parseVersion,
        long projectVersionNo,
        DocumentRole effectiveRole,
        UUID roleDecisionId,
        ProjectIdentityResolution identityResolution,
        UUID identityDecisionId,
        String sourceFactFingerprint,
        long authorityVersion,
        String createdBy,
        Instant createdAt) {

    public ContractCandidateSupportAuthority {
        Objects.requireNonNull(supportAuthorityId, "supportAuthorityId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
        requireLowercaseSha256(fileSha256, "fileSha256");
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        if (parseVersion <= 0) throw new IllegalArgumentException("parseVersion must be positive");
        if (projectVersionNo <= 0) throw new IllegalArgumentException("projectVersionNo must be positive");
        Objects.requireNonNull(effectiveRole, "effectiveRole must not be null");
        if (effectiveRole != DocumentRole.AWARD_NOTICE) {
            throw new IllegalArgumentException("effectiveRole must be AWARD_NOTICE");
        }
        Objects.requireNonNull(roleDecisionId, "roleDecisionId must not be null");
        Objects.requireNonNull(identityResolution, "identityResolution must not be null");
        if (identityResolution != ProjectIdentityResolution.CONFIRM_SAME_PROJECT) {
            throw new IllegalArgumentException("identityResolution must be CONFIRM_SAME_PROJECT");
        }
        Objects.requireNonNull(identityDecisionId, "identityDecisionId must not be null");
        requireLowercaseSha256(sourceFactFingerprint, "sourceFactFingerprint");
        if (authorityVersion <= 0) throw new IllegalArgumentException("authorityVersion must be positive");
        if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy must not be blank");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireLowercaseSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }
}
