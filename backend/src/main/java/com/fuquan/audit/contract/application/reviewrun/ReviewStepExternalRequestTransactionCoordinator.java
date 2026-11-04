package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits proof that an external request may have escaped before any HTTP call occurs. */
@Component
public class ReviewStepExternalRequestTransactionCoordinator {
    private final ContractReviewStepAttemptRepository attempts;
    private final Clock clock;

    public ReviewStepExternalRequestTransactionCoordinator(ContractReviewStepAttemptRepository attempts, Clock clock) {
        this.attempts = attempts; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewStepAttempt markRequested(
            ReviewStepStartTransactionCoordinator.StartedExecution started, String requestFingerprint) {
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid external request fingerprint");
        var current = attempts.findAttemptById(started.attempt().attemptId()).filter(value ->
                value.runId().equals(started.run().runId()) && value.stepId().equals(started.step().stepId()))
                .orElseThrow(() -> ReviewStepStartTransactionCoordinator.conflict("review step attempt is missing"));
        if (current.status() != ContractReviewStepStatus.RUNNING
                || current.externalCallState() != ReviewStepExternalCallState.NOT_STARTED)
            throw ReviewStepStartTransactionCoordinator.conflict("external request was already marked");
        var next = new ContractReviewStepAttempt(current.attemptId(), current.runId(), current.stepId(),
                current.attemptNo(), current.operationIdentity(), current.status(), current.leaseOwner(),
                current.leaseUntil(), current.claimedAt(), ReviewStepExternalCallState.REQUESTED,
                requestFingerprint, current.providerRequestId(), current.provider(), current.model(),
                current.startedAt(), current.completedAt(), current.safeReasonCode(),
                current.outputResourceType(), current.outputResourceId(), current.versionNo() + 1);
        return attempts.transition(current, next);
    }
}
