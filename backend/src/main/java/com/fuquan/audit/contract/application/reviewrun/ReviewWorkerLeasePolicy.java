package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Instant;
import java.util.Objects;

/** Pure lease validation. Database CAS, not this object, establishes execution ownership. */
public final class ReviewWorkerLeasePolicy {
    public enum Recovery { NONE, RECLAIM_NOT_STARTED, UNKNOWN }

    public ContractReviewStepAttempt claim(ContractReviewStepAttempt attempt, String owner,
            Instant now, Instant until) {
        validateLease(owner, now, until);
        if (attempt.status() != ContractReviewStepStatus.PENDING || attempt.leaseOwner() != null
                || attempt.leaseUntil() != null
                || attempt.externalCallState() != ReviewStepExternalCallState.NOT_STARTED) throw conflict();
        return new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(), attempt.stepId(),
                attempt.attemptNo(), attempt.operationIdentity(), ContractReviewStepStatus.RUNNING,
                owner, until, now, ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                now, null, null, null, null, attempt.versionNo() + 1);
    }

    public ContractReviewStepAttempt heartbeat(ContractReviewStepAttempt attempt, String owner,
            long version, Instant now, Instant until) {
        requireFence(attempt, owner, version, now);
        validateLease(owner, now, until);
        if (!until.isAfter(attempt.leaseUntil())) throw new IllegalArgumentException("lease must extend");
        return new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(), attempt.stepId(),
                attempt.attemptNo(), attempt.operationIdentity(), attempt.status(), owner, until,
                attempt.claimedAt(), attempt.externalCallState(), attempt.externalRequestFingerprint(),
                attempt.providerRequestId(), attempt.provider(), attempt.model(), attempt.startedAt(),
                attempt.completedAt(), attempt.safeReasonCode(), attempt.outputResourceType(),
                attempt.outputResourceId(), attempt.versionNo() + 1);
    }

    public void requireFence(ContractReviewStepAttempt attempt, String owner, long version, Instant now) {
        if (owner == null || !owner.equals(attempt.leaseOwner()) || attempt.versionNo() != version
                || attempt.status() != ContractReviewStepStatus.RUNNING || attempt.leaseUntil() == null
                || !attempt.leaseUntil().isAfter(now)) throw conflict();
    }

    public Recovery recovery(ContractReviewStepAttempt attempt, Instant now) {
        Objects.requireNonNull(now);
        if (attempt.status() != ContractReviewStepStatus.RUNNING || attempt.leaseOwner() == null
                || attempt.leaseUntil() == null || attempt.leaseUntil().isAfter(now)) return Recovery.NONE;
        return attempt.externalCallState() == ReviewStepExternalCallState.NOT_STARTED
                ? Recovery.RECLAIM_NOT_STARTED : Recovery.UNKNOWN;
    }

    private static void validateLease(String owner, Instant now, Instant until) {
        Objects.requireNonNull(now); Objects.requireNonNull(until);
        if (owner == null || !owner.matches("[A-Za-z0-9._:-]{1,100}") || !until.isAfter(now))
            throw new IllegalArgumentException("invalid worker lease");
    }

    public static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "review worker lease conflict");
    }
}
