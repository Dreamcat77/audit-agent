package com.fuquan.audit.contract.domain.reviewrun;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable run identity and frozen Contract/policy inputs. Phase 2A only creates PENDING. */
public record ContractReviewRun(
        UUID runId, UUID projectId, UUID contractId, long contractVersion,
        UUID sourceCandidateId, UUID sourceConfirmationId, int runSchemaVersion,
        String contractSnapshotCanonicalJson, String contractSnapshotSha256,
        String reviewProfileVersion, String ruleVersion, String modelPolicyVersion,
        String inputSnapshotIdentity, String requestedBy, ContractReviewRunStatus status,
        Instant requestedAt, Instant startedAt, Instant completedAt, Instant failedAt,
        Instant unknownAt, String safeReasonCode, long versionNo) {
    public ContractReviewRun {
        Objects.requireNonNull(runId); Objects.requireNonNull(projectId); Objects.requireNonNull(contractId);
        Objects.requireNonNull(contractSnapshotCanonicalJson);
        Objects.requireNonNull(reviewProfileVersion); Objects.requireNonNull(ruleVersion);
        Objects.requireNonNull(modelPolicyVersion); Objects.requireNonNull(requestedBy);
        Objects.requireNonNull(status); Objects.requireNonNull(requestedAt);
        if (contractVersion < 0 || runSchemaVersion <= 0 || versionNo < 0 || requestedBy.isBlank())
            throw new IllegalArgumentException("invalid review run identity");
        if (sourceConfirmationId != null && sourceCandidateId == null)
            throw new IllegalArgumentException("confirmation requires source candidate");
        requireHash(contractSnapshotSha256); requireHash(inputSnapshotIdentity);
        if (status == ContractReviewRunStatus.PENDING &&
                (startedAt != null || completedAt != null || failedAt != null || unknownAt != null || safeReasonCode != null))
            throw new IllegalArgumentException("pending run has no execution outcome");
    }
    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid SHA-256");
    }
}
