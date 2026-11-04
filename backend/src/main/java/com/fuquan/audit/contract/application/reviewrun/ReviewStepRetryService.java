package com.fuquan.audit.contract.application.reviewrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.application.AuthContext;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** An authorized retry is a new durable command; terminal attempt history is never updated. */
public class ReviewStepRetryService {
    public static final String SCOPE = "CONTRACT_REVIEW_STEP_RETRY";
    private static final Set<String> ROLES = Set.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL");
    private final ContractReviewWorkerRepository workers;
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final AuthContext auth;
    private final Clock clock;
    private final ReviewStepRetryPolicy policy;

    public ReviewStepRetryService(ContractReviewWorkerRepository workers, ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            IdempotencyPort idempotency, OperationAuditPort audit, AuthContext auth, Clock clock, ReviewStepRetryPolicy policy) {
        this.workers = workers; this.runs = runs; this.steps = steps; this.attempts = attempts;
        this.idempotency = idempotency; this.audit = audit; this.auth = auth; this.clock = clock; this.policy = policy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryResult retry(RetryReviewStepCommand command) {
        var actor = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        if (actor.roles().stream().noneMatch(ROLES::contains)) throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        var run = runs.findByIdForUpdate(command.runId()).filter(value -> value.projectId().equals(command.projectId())
                && value.contractId().equals(command.contractId())).orElseThrow(ReviewStepRetryService::notFound);
        String fingerprint = fingerprint(command, actor.userId());
        var prior = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (prior.isPresent()) {
            var record = prior.orElseThrow();
            if (!record.actorId().equals(actor.userId()) || !record.requestFingerprint().equals(fingerprint)
                    || !"CONTRACT_REVIEW_STEP".equals(record.resourceType())
                    || !command.stepId().toString().equals(record.resourceId())
                    || record.status() != IdempotencyStatus.SUCCEEDED
                    || !"CONTRACT_REVIEW_STEP_ATTEMPT".equals(record.resultResourceType())) throw conflict();
            UUID attemptId;
            try { attemptId = UUID.fromString(record.resultResourceId()); }
            catch (RuntimeException invalid) { throw conflict(); }
            var result = attempts.findAttemptById(attemptId).filter(value -> value.runId().equals(run.runId())
                    && value.stepId().equals(command.stepId()) && value.attemptNo() == command.previousAttemptNo() + 1)
                    .orElseThrow(ReviewStepRetryService::conflict);
            return new RetryResult(result, true);
        }
        var step = steps.findStepById(command.stepId()).filter(value -> value.runId().equals(run.runId()))
                .orElseThrow(ReviewStepRetryService::notFound);
        var previous = attempts.findAttemptById(command.previousAttemptId()).filter(value ->
                value.runId().equals(run.runId()) && value.stepId().equals(step.stepId())).orElseThrow(ReviewStepRetryService::notFound);
        if (run.versionNo() != command.expectedRunVersion() || step.versionNo() != command.expectedStepVersion()
                || step.currentAttemptNo() != previous.attemptNo() || previous.attemptNo() != command.previousAttemptNo()
                || previous.versionNo() != command.previousAttemptVersion() || previous.status() != command.previousStatus()
                || previous.externalCallState() != command.previousExternalCallState()
                || (run.status() != ContractReviewRunStatus.RUNNING && run.status() != ContractReviewRunStatus.FAILED)) throw conflict();
        policy.requireSafe(step, previous);
        if (step.stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
            var f02 = idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE,
                    FrozenF02ReviewStepExecutor.internalKey(run.runId(), step.stepId(), previous.attemptId()));
            if (f02.isPresent() && f02.orElseThrow().status() != IdempotencyStatus.FAILED) throw conflict();
        }
        var now = clock.instant();
        UUID nextId = UUID.randomUUID();
        String operationIdentity = ContractSnapshotCanonicalizer.sha256("review-worker-retry-v1:" + run.runId()
                + ":" + step.stepId() + ":" + nextId + ":" + (previous.attemptNo() + 1));
        var next = new ContractReviewStepAttempt(nextId, run.runId(), step.stepId(), previous.attemptNo() + 1,
                operationIdentity, ContractReviewStepStatus.PENDING, null, null, null,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, null, null, null, null, null, 0);
        var nextStep = new ContractReviewStep(step.stepId(), step.runId(), step.stepType(), step.required(),
                ContractReviewStepStatus.PENDING, step.stepPolicyVersion(), next.attemptNo(), null, null,
                null, null, null, step.versionNo() + 1);
        var resumed = new ContractReviewRun(run.runId(), run.projectId(), run.contractId(), run.contractVersion(),
                run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(), run.contractSnapshotCanonicalJson(),
                run.contractSnapshotSha256(), run.reviewProfileVersion(), run.ruleVersion(), run.modelPolicyVersion(),
                run.inputSnapshotIdentity(), run.requestedBy(), ContractReviewRunStatus.RUNNING, run.requestedAt(),
                run.startedAt(), null, null, null, null, run.versionNo() + 1);
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(), actor.userId(),
                "CONTRACT_REVIEW_STEP", step.stepId().toString(), fingerprint, "CONTRACT_REVIEW_STEP_ATTEMPT",
                nextId.toString(), command.metadata().requestId(), command.metadata().traceId(), now));
        if (!claim.acquired()) throw conflict();
        try {
            attempts.insert(next);
            workers.advanceRetry(step, nextStep, run, resumed);
            idempotency.markSucceeded(claim.record(), "CONTRACT_REVIEW_STEP_ATTEMPT", nextId.toString(), now);
            audit.append(new OperationAuditEvent("CONTRACT_REVIEW_STEP_RETRIED", "CONTRACT", "retry",
                    actor.userId(), actor.username(), "CONTRACT_REVIEW_STEP_ATTEMPT", nextId.toString(), null,
                    OperationAuditResultStatus.SUCCESS, "PENDING", "contract review retry",
                    "{\"previousAttemptId\":\"" + previous.attemptId() + "\",\"reasonSha256\":\""
                            + ContractSnapshotCanonicalizer.sha256(command.reason()) + "\"}",
                    command.metadata().requestId(), command.metadata().traceId(), command.metadata().sourceIp(), now));
            return new RetryResult(next, false);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }
    private static String fingerprint(RetryReviewStepCommand c, String actor) {
        try {
            return ContractSnapshotCanonicalizer.sha256(new ObjectMapper().writeValueAsString(List.of(
                    SCOPE, actor, c.projectId().toString(), c.contractId().toString(), c.runId().toString(),
                    c.stepId().toString(), c.expectedRunVersion(), c.expectedStepVersion(), c.previousAttemptId().toString(),
                    c.previousAttemptNo(), c.previousAttemptVersion(), c.previousStatus().name(),
                    c.previousExternalCallState().name(), ReviewStepRetryPolicy.VERSION, c.reason())));
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("retry fingerprint encoding failed", invalid); }
    }
    private static BusinessException conflict() { return ReviewWorkerLeasePolicy.conflict(); }
    private static BusinessException notFound() { return new BusinessException(BusinessErrorCode.NOT_FOUND, "review step not found"); }
    public record RetryResult(ContractReviewStepAttempt attempt, boolean replayed) { }
}
