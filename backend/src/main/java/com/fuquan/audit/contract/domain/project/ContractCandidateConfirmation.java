package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An append-only human snapshot. Candidate/extraction proposal fields are never changed by this record. */
public record ContractCandidateConfirmation(
        UUID confirmationId, UUID projectId, UUID candidateId,
        long baseCandidateVersionNo, long resultCandidateVersionNo,
        UUID sourceFileId, UUID sourceParseResultId, UUID sourceRoleDecisionId,
        UUID sourceIdentityDecisionId, int payloadSchemaVersion, String payloadFingerprint,
        String canonicalPayloadJson, String confirmedBy, Instant confirmedAt,
        UUID supersedesConfirmationId, Instant createdAt) {
    public ContractCandidateConfirmation {
        Objects.requireNonNull(confirmationId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(candidateId);
        Objects.requireNonNull(sourceFileId);
        Objects.requireNonNull(sourceParseResultId);
        Objects.requireNonNull(sourceRoleDecisionId);
        Objects.requireNonNull(sourceIdentityDecisionId);
        Objects.requireNonNull(payloadFingerprint);
        Objects.requireNonNull(canonicalPayloadJson);
        Objects.requireNonNull(confirmedBy);
        Objects.requireNonNull(confirmedAt);
        Objects.requireNonNull(createdAt);
        if (baseCandidateVersionNo < 0 || resultCandidateVersionNo != baseCandidateVersionNo + 1
                || confirmedBy.isBlank() || !payloadFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("candidate confirmation identity is invalid");
        }
        if (confirmationId.equals(supersedesConfirmationId)) {
            throw new IllegalArgumentException("confirmation cannot supersede itself");
        }
    }
}
