package com.fuquan.audit.contract.domain.reviewrun;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable first attempt identity with lease-ready fields; no claim logic in Phase 2A. */
public record ContractReviewStepAttempt(
        UUID attemptId, UUID runId, UUID stepId, int attemptNo, String operationIdentity,
        ContractReviewStepStatus status, String leaseOwner, Instant leaseUntil, Instant claimedAt,
        ReviewStepExternalCallState externalCallState, String externalRequestFingerprint,
        String providerRequestId, String provider, String model, Instant startedAt,
        Instant completedAt, String safeReasonCode, String outputResourceType,
        UUID outputResourceId, long versionNo) {
    public ContractReviewStepAttempt {
        Objects.requireNonNull(attemptId); Objects.requireNonNull(runId); Objects.requireNonNull(stepId);
        Objects.requireNonNull(status); Objects.requireNonNull(externalCallState);
        ContractReviewRun.requireHash(operationIdentity);
        if (attemptNo <= 0 || versionNo < 0) throw new IllegalArgumentException("invalid attempt versions");
        if (status == ContractReviewStepStatus.PENDING && (leaseOwner != null || leaseUntil != null
                || claimedAt != null || externalCallState != ReviewStepExternalCallState.NOT_STARTED
                || externalRequestFingerprint != null || providerRequestId != null || provider != null
                || model != null || startedAt != null || completedAt != null || safeReasonCode != null
                || outputResourceType != null || outputResourceId != null))
            throw new IllegalArgumentException("pending attempt must not claim execution");
    }
}
