package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewStepFailureTransactionCoordinator {
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final ContractModelCallAuditRepository modelCalls;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final ReviewRunStatusAggregator aggregator;
    private final Clock clock;

    @Autowired
    public ReviewStepFailureTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ContractModelCallAuditRepository modelCalls,
            IdempotencyPort idempotency, OperationAuditPort audit,
            ReviewRunStatusAggregator aggregator, Clock clock) {
        this.runs = runs; this.steps = steps; this.attempts = attempts; this.modelCalls = modelCalls;
        this.idempotency = idempotency; this.audit = audit; this.aggregator = aggregator; this.clock = clock;
    }

    public ReviewStepFailureTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            IdempotencyPort idempotency, OperationAuditPort audit,
            ReviewRunStatusAggregator aggregator, Clock clock) {
        this(runs, steps, attempts, null, idempotency, audit, aggregator, clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void fail(ReviewStepStartTransactionCoordinator.StartedExecution started,
            String safeReasonCode) {
        fail(started, safeReasonCode, List.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void fail(ReviewStepStartTransactionCoordinator.StartedExecution started,
            String safeReasonCode, List<ContractModelCallAudit> calls) {
        String reason = safeReason(safeReasonCode);
        Instant now = clock.instant();
        ContractReviewRun currentRun = runs.findByIdForUpdate(started.run().runId()).filter(value ->
                value.status() == ContractReviewRunStatus.RUNNING
                        || value.status() == ContractReviewRunStatus.UNKNOWN).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review run is not running"));
        var currentAttempt = attempts.findAttemptById(started.attempt().attemptId()).filter(value ->
                value.runId().equals(started.run().runId()) && value.stepId().equals(started.step().stepId())
                && value.status() == ContractReviewStepStatus.RUNNING).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review step attempt is not running"));
        var currentStep = steps.findStepById(started.step().stepId()).filter(value ->
                value.runId().equals(started.run().runId()) && value.status() == ContractReviewStepStatus.RUNNING)
                .orElseThrow(() -> ReviewStepStartTransactionCoordinator.conflict("review step is not running"));
        validateCalls(started.step(), currentAttempt, calls);
        if (!calls.isEmpty()) modelCalls.insertAll(calls);
        ReviewStepExternalCallState externalState = currentAttempt.externalCallState()
                == ReviewStepExternalCallState.REQUESTED
                ? ReviewStepExternalCallState.COMPLETED : ReviewStepExternalCallState.NOT_STARTED;
        var failedAttempt = new ContractReviewStepAttempt(currentAttempt.attemptId(), currentAttempt.runId(),
                currentAttempt.stepId(), currentAttempt.attemptNo(), currentAttempt.operationIdentity(),
                ContractReviewStepStatus.FAILED, currentAttempt.leaseOwner(), currentAttempt.leaseUntil(),
                currentAttempt.claimedAt(), externalState,
                currentAttempt.externalRequestFingerprint(), currentAttempt.providerRequestId(),
                currentAttempt.provider(), currentAttempt.model(), currentAttempt.startedAt(), now, reason,
                null, null, currentAttempt.versionNo() + 1);
        attempts.transition(currentAttempt, failedAttempt);
        var failedStep = new ContractReviewStep(currentStep.stepId(), currentStep.runId(),
                currentStep.stepType(), currentStep.required(), ContractReviewStepStatus.FAILED,
                currentStep.stepPolicyVersion(), currentStep.currentAttemptNo(), null, null, reason,
                currentStep.startedAt(), now, currentStep.versionNo() + 1);
        failedStep = steps.transition(currentStep, failedStep);
        ContractReviewRunStatus runStatus = aggregator.status(steps.findStepsByRunId(currentRun.runId()));
        var failedRun = transitionRun(currentRun, runStatus,
                runStatus == ContractReviewRunStatus.FAILED ? reason : null, now);
        idempotency.markFailed(started.idempotency(), now);
        audit.append(ReviewStepStartTransactionCoordinator.audit("CONTRACT_REVIEW_STEP_FAILED", "fail",
                started.actor(), failedRun, failedStep, started.command().metadata(),
                OperationAuditResultStatus.FAILURE, "FAILED", now));
    }

    private void validateCalls(ContractReviewStep step, ContractReviewStepAttempt attempt,
            List<ContractModelCallAudit> calls) {
        if (calls.isEmpty()) return;
        if (step.stepType() != ContractReviewStepType.SEMANTIC_AI) {
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit is only valid for semantic review");
        }
        if (modelCalls == null || attempt.externalRequestFingerprint() == null) {
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit repository is unavailable");
        }
        var ordinals = new HashSet<Integer>();
        if (calls.stream().anyMatch(call -> call.factExtractionAttemptId() != null
                || !attempt.attemptId().equals(call.reviewStepAttemptId())
                || attempt.attemptNo() != call.attemptNo() || call.scope() != null
                || call.batchStatus() != null
                || !attempt.externalRequestFingerprint().equals(call.requestFingerprint())
                || !ordinals.add(call.callOrdinal()))) {
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit identity is invalid");
        }
    }

    private ContractReviewRun transitionRun(ContractReviewRun run, ContractReviewRunStatus status,
            String reason, Instant now) {
        if (run.status() == ContractReviewRunStatus.UNKNOWN
                && status == ContractReviewRunStatus.UNKNOWN) return run;
        return runs.transition(run, new ContractReviewRun(run.runId(), run.projectId(), run.contractId(),
                run.contractVersion(), run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(),
                run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                run.ruleVersion(), run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(), status,
                run.requestedAt(), run.startedAt(), null,
                status == ContractReviewRunStatus.FAILED ? now : null, null, reason, run.versionNo() + 1));
    }

    static String safeReason(String value) {
        if (value == null || value.isBlank() || value.length() > 100)
            throw new IllegalArgumentException("invalid safe reason code");
        return value;
    }
}
