package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Synchronous orchestration only; every durable write is delegated to a short transaction coordinator. */
@Service
public class ReviewStepExecutionService {
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private final AuthContext auth;
    private final IdempotencyPort idempotency;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final ReviewStepStartTransactionCoordinator starts;
    private final FrozenReviewInputLoader inputs;
    private final ReviewStepCompleteTransactionCoordinator completes;
    private final ReviewStepFailureTransactionCoordinator failures;
    private final ReviewStepUnknownTransactionCoordinator unknowns;
    private final ReviewStepExternalRequestTransactionCoordinator externalRequests;
    private final ReviewStepOutputReadService outputs;
    private final FrozenF02ResultMapper f02Mapper;
    private final Map<ContractReviewStepType, ReviewStepExecutor> executors;

    public ReviewStepExecutionService(AuthContext auth, IdempotencyPort idempotency,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ReviewStepStartTransactionCoordinator starts, FrozenReviewInputLoader inputs,
            ReviewStepCompleteTransactionCoordinator completes,
            ReviewStepFailureTransactionCoordinator failures,
            ReviewStepUnknownTransactionCoordinator unknowns,
            ReviewStepExternalRequestTransactionCoordinator externalRequests,
            ReviewStepOutputReadService outputs, FrozenF02ResultMapper f02Mapper,
            List<ReviewStepExecutor> executors) {
        this.auth = auth; this.idempotency = idempotency; this.steps = steps; this.attempts = attempts;
        this.starts = starts; this.inputs = inputs; this.completes = completes; this.failures = failures;
        this.unknowns = unknowns; this.externalRequests = externalRequests; this.outputs = outputs;
        this.f02Mapper = f02Mapper;
        var registry = new EnumMap<ContractReviewStepType, ReviewStepExecutor>(ContractReviewStepType.class);
        for (ReviewStepExecutor executor : executors) {
            ContractReviewStepType type = executor.stepType();
            if (type == null || type == ContractReviewStepType.MATERIAL_VALIDATION
                    || registry.putIfAbsent(type, executor) != null)
                throw new IllegalStateException("duplicate or invalid review step executor");
        }
        this.executors = Map.copyOf(registry);
    }

    public StepExecutionResult execute(ReviewStepStartTransactionCoordinator.StartCommand command) {
        if (!SAFE_KEY.matcher(command.idempotencyKey()).matches())
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        CurrentUser actor = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        var prior = idempotency.findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, command.idempotencyKey());
        if (prior.isPresent()) return replay(prior.orElseThrow(), command, actor);

        var started = starts.start(command, actor);
        ReviewStepExecutor executor = executors.get(started.step().stepType());
        if (executor == null) {
            failures.fail(started, "STEP_EXECUTOR_UNAVAILABLE");
            throw new ReviewStepProvenFailureException("STEP_EXECUTOR_UNAVAILABLE",
                    new IllegalStateException("review step executor is unavailable"));
        }
        StepComputationResult computed;
        try {
            FrozenReviewInput input = inputs.load(command.projectId(), command.contractId(), command.runId());
            computed = executor.execute(input, started.context(externalRequests));
        } catch (ReviewStepOutcomeUnknownException unknown) {
            if (unknown.modelCalls().isEmpty()) unknowns.unknown(started, unknown.safeReasonCode());
            else unknowns.unknown(started, unknown.safeReasonCode(), unknown.modelCalls());
            throw unknown;
        } catch (ReviewStepProvenFailureException failed) {
            if (failed.modelCalls().isEmpty()) failures.fail(started, failed.safeReasonCode());
            else failures.fail(started, failed.safeReasonCode(), failed.modelCalls());
            throw failed;
        } catch (BusinessException invalid) {
            failures.fail(started, "STEP_COMPUTATION_INVALID");
            throw invalid;
        } catch (RuntimeException failed) {
            failures.fail(started, "STEP_EXECUTION_FAILED");
            throw new ReviewStepProvenFailureException("STEP_EXECUTION_FAILED", failed);
        }
        // COMPLETE is deliberately outside the computation catch: a stale durable writer must not
        // be overwritten with FAILED after a transaction/CAS conflict.
        completes.complete(started, computed);
        return new StepExecutionResult(outputs.getStep(command.projectId(), command.contractId(),
                command.runId(), command.stepId()), false);
    }

    private StepExecutionResult replay(IdempotencyRecord record,
            ReviewStepStartTransactionCoordinator.StartCommand command, CurrentUser actor) {
        if (!ReviewStepStartTransactionCoordinator.SCOPE.equals(record.scope())
                || !record.actorId().equals(actor.userId())
                || !"CONTRACT_REVIEW_STEP".equals(record.resourceType())
                || !record.resourceId().equals(command.stepId().toString())) throw conflict();
        var output = outputs.getStep(command.projectId(), command.contractId(), command.runId(), command.stepId());
        ContractReviewStepAttempt attempt = output.attempts().stream()
                .filter(value -> value.attemptNo() == output.step().currentAttemptNo())
                .findFirst().orElseThrow(ReviewStepExecutionService::conflict);
        String fingerprint = ReviewStepStartTransactionCoordinator.fingerprint(
                actor, output.run(), output.step(), attempt);
        if (!fingerprint.equals(record.requestFingerprint())) throw conflict();
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            return recoverCompletedF02(record, command, actor, output, attempt);
        }
        if (record.status() != IdempotencyStatus.SUCCEEDED
                || output.step().status() != ContractReviewStepStatus.COMPLETED
                || output.step().outputResourceType() == null || output.step().outputResourceId() == null
                || !output.step().outputResourceType().equals(record.resultResourceType())
                || !output.step().outputResourceId().toString().equals(record.resultResourceId())) throw conflict();
        return new StepExecutionResult(output, true);
    }

    private StepExecutionResult recoverCompletedF02(IdempotencyRecord operation,
            ReviewStepStartTransactionCoordinator.StartCommand command, CurrentUser actor,
            ReviewStepOutputReadService.StepOutput output, ContractReviewStepAttempt attempt) {
        if (output.step().stepType() != ContractReviewStepType.F02_DETERMINISTIC
                || output.step().status() != ContractReviewStepStatus.RUNNING
                || attempt.status() != ContractReviewStepStatus.RUNNING
                || attempt.leaseOwner() != null || attempt.leaseUntil() != null
                || !"CONTRACT_REVIEW_STEP_ATTEMPT".equals(operation.resultResourceType())
                || !attempt.attemptId().toString().equals(operation.resultResourceId())) throw conflict();
        String internalKey = FrozenF02ReviewStepExecutor.internalKey(
                output.run().runId(), output.step().stepId(), attempt.attemptId());
        IdempotencyRecord f02 = idempotency.findByScopeAndKey(
                ContractReviewService.IDEMPOTENCY_SCOPE, internalKey).filter(value ->
                        value.status() == IdempotencyStatus.SUCCEEDED
                                && value.actorId().equals(actor.userId())
                                && "CONTRACT".equals(value.resourceType())
                                && output.run().contractId().toString().equals(value.resourceId())
                                && "AUDIT_TASK".equals(value.resultResourceType())
                                && value.resultResourceId() != null)
                .orElseThrow(ReviewStepExecutionService::conflict);
        UUID taskId;
        try {
            taskId = UUID.fromString(f02.resultResourceId());
        } catch (IllegalArgumentException invalid) {
            throw conflict();
        }
        var durable = outputs.validateF02Output(
                output.run(), output.step(), attempt, taskId, actor.userId());
        var started = new ReviewStepStartTransactionCoordinator.StartedExecution(
                output.run(), output.step(), attempt, operation, actor, command, List.of());
        FrozenReviewInput frozen = inputs.load(command.projectId(), command.contractId(), command.runId());
        StepComputationResult recovered = f02Mapper.map(frozen, started.context(externalRequests),
                taskId, durable.checks());
        completes.complete(started, recovered);
        return new StepExecutionResult(outputs.getStep(command.projectId(), command.contractId(),
                command.runId(), command.stepId()), true);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "review step operation is in progress or does not match the durable command");
    }

    public record StepExecutionResult(ReviewStepOutputReadService.StepOutput output, boolean replayed) { }
}
