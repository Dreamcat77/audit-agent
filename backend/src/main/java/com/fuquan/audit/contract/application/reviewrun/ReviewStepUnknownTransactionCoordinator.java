package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewStepUnknownTransactionCoordinator {
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final OperationAuditPort audit;
    private final ReviewRunStatusAggregator aggregator;
    private final Clock clock;
    private final ContractModelCallAuditRepository modelCalls;

    public ReviewStepUnknownTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            OperationAuditPort audit, ReviewRunStatusAggregator aggregator, Clock clock) {
        this(runs, steps, attempts, audit, aggregator, clock, null);
    }

    @Autowired
    public ReviewStepUnknownTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            OperationAuditPort audit, ReviewRunStatusAggregator aggregator, Clock clock,
            ContractModelCallAuditRepository modelCalls) {
        this.runs = runs; this.steps = steps; this.attempts = attempts;
        this.audit = audit; this.aggregator = aggregator; this.clock = clock;
        this.modelCalls = modelCalls;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void unknown(ReviewStepStartTransactionCoordinator.StartedExecution started,
            String safeReasonCode) {
        unknown(started, safeReasonCode, List.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void unknown(ReviewStepStartTransactionCoordinator.StartedExecution started,
            String safeReasonCode, List<ContractModelCallAudit> calls) {
        String reason = ReviewStepFailureTransactionCoordinator.safeReason(safeReasonCode);
        var observedCalls = List.copyOf(calls);
        Instant now = clock.instant();
        ContractReviewRun currentRun = runs.findByIdForUpdate(started.run().runId()).filter(value ->
                value.status() == ContractReviewRunStatus.RUNNING
                        || value.status() == ContractReviewRunStatus.UNKNOWN).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review run is not running"));
        var currentAttempt = attempts.findAttemptById(started.attempt().attemptId()).filter(value ->
                value.runId().equals(started.run().runId()) && value.stepId().equals(started.step().stepId())
                        && value.status() == ContractReviewStepStatus.RUNNING).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review step attempt is not running"));
        if (currentAttempt.externalCallState() != ReviewStepExternalCallState.REQUESTED
                || currentAttempt.externalRequestFingerprint() == null)
            throw ReviewStepStartTransactionCoordinator.conflict(
                    "unknown outcome requires a durable external request identity");
        var currentStep = steps.findStepById(started.step().stepId()).filter(value ->
                value.runId().equals(started.run().runId()) && value.status() == ContractReviewStepStatus.RUNNING)
                .orElseThrow(() -> ReviewStepStartTransactionCoordinator.conflict("review step is not running"));
        // This is the synchronous closure, not an alternate writer for worker-owned leases.
        if (currentAttempt.leaseOwner() != null || currentAttempt.leaseUntil() != null
                || started.attempt().leaseOwner() != null || started.attempt().leaseUntil() != null
                || !currentRun.requestedBy().equals(started.actor().userId())
                || currentAttempt.attemptNo() != started.attempt().attemptNo()
                || currentStep.currentAttemptNo() != currentAttempt.attemptNo()
                || !currentAttempt.operationIdentity().equals(started.attempt().operationIdentity()))
            throw ReviewStepStartTransactionCoordinator.conflict("synchronous unknown attempt identity is invalid");
        validateCalls(currentStep, currentAttempt, observedCalls);
        var unknownAttempt = new ContractReviewStepAttempt(currentAttempt.attemptId(), currentAttempt.runId(),
                currentAttempt.stepId(), currentAttempt.attemptNo(), currentAttempt.operationIdentity(),
                ContractReviewStepStatus.UNKNOWN, currentAttempt.leaseOwner(), currentAttempt.leaseUntil(),
                currentAttempt.claimedAt(), ReviewStepExternalCallState.OUTCOME_UNKNOWN,
                currentAttempt.externalRequestFingerprint(), currentAttempt.providerRequestId(),
                currentAttempt.provider(), currentAttempt.model(), currentAttempt.startedAt(), now, reason,
                null, null, currentAttempt.versionNo() + 1);
        // Run row lock and attempt CAS precede insert; all UNKNOWN/audit facts share this transaction.
        attempts.transition(currentAttempt, unknownAttempt);
        if (!observedCalls.isEmpty()) modelCalls.insertAll(observedCalls);
        var unknownStep = new ContractReviewStep(currentStep.stepId(), currentStep.runId(),
                currentStep.stepType(), currentStep.required(), ContractReviewStepStatus.UNKNOWN,
                currentStep.stepPolicyVersion(), currentStep.currentAttemptNo(), null, null, reason,
                currentStep.startedAt(), now, currentStep.versionNo() + 1);
        unknownStep = steps.transition(currentStep, unknownStep);
        ContractReviewRunStatus runStatus = aggregator.status(steps.findStepsByRunId(currentRun.runId()));
        var unknownRun = currentRun.status() == ContractReviewRunStatus.UNKNOWN
                && runStatus == ContractReviewRunStatus.UNKNOWN ? currentRun
                : runs.transition(currentRun, new ContractReviewRun(currentRun.runId(),
                        currentRun.projectId(), currentRun.contractId(), currentRun.contractVersion(),
                        currentRun.sourceCandidateId(), currentRun.sourceConfirmationId(),
                        currentRun.runSchemaVersion(), currentRun.contractSnapshotCanonicalJson(),
                        currentRun.contractSnapshotSha256(), currentRun.reviewProfileVersion(),
                        currentRun.ruleVersion(), currentRun.modelPolicyVersion(),
                        currentRun.inputSnapshotIdentity(), currentRun.requestedBy(), runStatus,
                        currentRun.requestedAt(), currentRun.startedAt(), null, null,
                        runStatus == ContractReviewRunStatus.UNKNOWN ? now : null,
                        runStatus == ContractReviewRunStatus.UNKNOWN ? reason : null,
                        currentRun.versionNo() + 1));
        audit.append(ReviewStepStartTransactionCoordinator.audit("CONTRACT_REVIEW_STEP_UNKNOWN", "unknown",
                started.actor(), unknownRun, unknownStep, started.command().metadata(),
                OperationAuditResultStatus.FAILURE, "UNKNOWN", now));
        // Deliberately leave the original idempotency record IN_PROGRESS: outcome is not proven.
    }

    private void validateCalls(ContractReviewStep step, ContractReviewStepAttempt attempt,
            List<ContractModelCallAudit> calls) {
        if (calls.isEmpty()) return;
        if (step.stepType() != ContractReviewStepType.SEMANTIC_AI || modelCalls == null)
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit owner is invalid");
        var ordinals = new HashSet<Integer>();
        var identities = new HashSet<UUID>();
        if (calls.stream().anyMatch(call -> call.factExtractionAttemptId() != null
                || !attempt.attemptId().equals(call.reviewStepAttemptId())
                || attempt.attemptNo() != call.attemptNo() || call.scope() != null
                || call.batchStatus() != null
                || !attempt.externalRequestFingerprint().equals(call.requestFingerprint())
                || !ordinals.add(call.callOrdinal()) || !identities.add(call.callAuditId())))
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit identity is invalid");
    }
}
