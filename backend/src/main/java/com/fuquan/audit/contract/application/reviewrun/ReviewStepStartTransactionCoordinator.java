package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewStepStartTransactionCoordinator {
    public static final String SCOPE = "REVIEW_STEP_EXECUTE";
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final ContractReviewExecutionPolicyResolver policies;
    private final Clock clock;

    public ReviewStepStartTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            IdempotencyPort idempotency, OperationAuditPort audit,
            ContractReviewExecutionPolicyResolver policies, Clock clock) {
        this.runs = runs; this.steps = steps; this.attempts = attempts;
        this.idempotency = idempotency; this.audit = audit; this.policies = policies; this.clock = clock;
    }

    @Transactional
    public StartedExecution start(StartCommand command, CurrentUser actor) {
        ContractReviewRun run = runs.findByIdForUpdate(command.runId()).filter(value ->
                value.projectId().equals(command.projectId()) && value.contractId().equals(command.contractId()))
                .orElseThrow(ReviewStepStartTransactionCoordinator::notFound);
        ContractReviewStep step = steps.findStepById(command.stepId()).filter(value ->
                value.runId().equals(run.runId())).orElseThrow(ReviewStepStartTransactionCoordinator::notFound);
        if (step.stepType() == ContractReviewStepType.MATERIAL_VALIDATION)
            throw conflict("material validation is not executable");
        if (step.status() != ContractReviewStepStatus.PENDING || step.currentAttemptNo() != 1
                || step.outputResourceId() != null || step.outputResourceType() != null)
            throw conflict("review step first attempt is not pending");
        ContractReviewStepAttempt attempt = attempts.findAttemptsByRunId(run.runId()).stream()
                .filter(value -> value.stepId().equals(step.stepId()) && value.attemptNo() == 1)
                .findFirst().orElseThrow(() -> conflict("review step first attempt is missing"));
        if (attempt.status() != ContractReviewStepStatus.PENDING
                || attempt.externalCallState() != ReviewStepExternalCallState.NOT_STARTED)
            throw conflict("review step first attempt is not pending");
        if (run.status() != ContractReviewRunStatus.PENDING && run.status() != ContractReviewRunStatus.RUNNING)
            throw conflict("review run is terminal");

        List<ContractRuleConfiguration> f02Configurations = List.of();
        if (step.stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
            var resolved = policies.resolveCurrentF02();
            if (!resolved.ruleVersion().equals(run.ruleVersion())
                    || !step.stepPolicyVersion().equals(run.ruleVersion()))
                throw conflict("frozen F02 policy no longer matches current execution policy");
            f02Configurations = resolved.enabledF02Configurations();
        }
        String fingerprint = fingerprint(actor, run, step, attempt);
        Instant now = clock.instant();
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(),
                actor.userId(), "CONTRACT_REVIEW_STEP", step.stepId().toString(), fingerprint,
                "CONTRACT_REVIEW_STEP_ATTEMPT", attempt.attemptId().toString(),
                command.metadata().requestId(), command.metadata().traceId(), now));
        if (!claim.acquired()) {
            if (!claim.record().requestFingerprint().equals(fingerprint))
                throw conflict("idempotency key fingerprint conflict");
            throw conflict("review step operation is already durable");
        }
        try {
            var runningAttempt = new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(),
                    attempt.stepId(), attempt.attemptNo(), attempt.operationIdentity(),
                    ContractReviewStepStatus.RUNNING, null, null, now,
                    ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                    now, null, null, null, null, attempt.versionNo() + 1);
            runningAttempt = attempts.transition(attempt, runningAttempt);
            var runningStep = new ContractReviewStep(step.stepId(), step.runId(), step.stepType(),
                    step.required(), ContractReviewStepStatus.RUNNING, step.stepPolicyVersion(),
                    step.currentAttemptNo(), null, null, null, now, null, step.versionNo() + 1);
            runningStep = steps.transition(step, runningStep);
            ContractReviewRun currentRun = run;
            ContractReviewRun runningRun = currentRun;
            if (currentRun.status() == ContractReviewRunStatus.PENDING) {
                runningRun = new ContractReviewRun(currentRun.runId(), currentRun.projectId(),
                        currentRun.contractId(), currentRun.contractVersion(), currentRun.sourceCandidateId(),
                        currentRun.sourceConfirmationId(), currentRun.runSchemaVersion(),
                        currentRun.contractSnapshotCanonicalJson(), currentRun.contractSnapshotSha256(),
                        currentRun.reviewProfileVersion(), currentRun.ruleVersion(), currentRun.modelPolicyVersion(),
                        currentRun.inputSnapshotIdentity(), currentRun.requestedBy(), ContractReviewRunStatus.RUNNING,
                        currentRun.requestedAt(), now, null, null, null, null, currentRun.versionNo() + 1);
                runningRun = runs.transition(currentRun, runningRun);
            }
            audit.append(audit("CONTRACT_REVIEW_STEP_STARTED", "start", actor, runningRun,
                    runningStep, command.metadata(), OperationAuditResultStatus.SUCCESS, "RUNNING", now));
            return new StartedExecution(runningRun, runningStep, runningAttempt, claim.record(), actor,
                    command, f02Configurations);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    static String fingerprint(CurrentUser actor, ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt) {
        return ContractSnapshotCanonicalizer.sha256(String.join(":", "review-step-execute-v1",
                actor.userId(), run.projectId().toString(), run.contractId().toString(),
                run.runId().toString(), run.inputSnapshotIdentity(), step.stepId().toString(),
                step.stepType().name(), step.stepPolicyVersion(), attempt.attemptId().toString(),
                attempt.operationIdentity()));
    }

    static OperationAuditEvent audit(String eventType, String action, CurrentUser actor,
            ContractReviewRun run, ContractReviewStep step, ProjectRequestMetadata metadata,
            OperationAuditResultStatus result, String businessResult, Instant at) {
        return new OperationAuditEvent(eventType, "CONTRACT", action, actor.userId(), actor.username(),
                "CONTRACT_REVIEW_STEP", step.stepId().toString(), null, result, businessResult,
                "contract review step " + action, "{\"runId\":\"" + run.runId()
                        + "\",\"stepType\":\"" + step.stepType() + "\"}",
                metadata.requestId(), metadata.traceId(), metadata.sourceIp(), at);
    }

    private static BusinessException notFound() {
        return new BusinessException(BusinessErrorCode.NOT_FOUND, "review step not found");
    }
    static BusinessException conflict(String reason) {
        return new BusinessException(BusinessErrorCode.CONFLICT, reason);
    }

    public record StartCommand(UUID projectId, UUID contractId, UUID runId, UUID stepId,
            String idempotencyKey, ProjectRequestMetadata metadata) {
        public StartCommand {
            Objects.requireNonNull(projectId); Objects.requireNonNull(contractId);
            Objects.requireNonNull(runId); Objects.requireNonNull(stepId); Objects.requireNonNull(metadata);
            if (idempotencyKey == null || idempotencyKey.isBlank())
                throw new IllegalArgumentException("blank Idempotency-Key");
        }
    }

    public record StartedExecution(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt, IdempotencyRecord idempotency, CurrentUser actor,
            StartCommand command, List<ContractRuleConfiguration> f02Configurations) {
        public StartedExecution {
            f02Configurations = List.copyOf(f02Configurations);
        }
        public ReviewExecutionContext context(ReviewStepExternalRequestTransactionCoordinator externalRequests) {
            return new ReviewExecutionContext(run, step, attempt, actor, command.metadata(), f02Configurations,
                    fingerprint -> externalRequests.markRequested(this, fingerprint));
        }
    }
}
