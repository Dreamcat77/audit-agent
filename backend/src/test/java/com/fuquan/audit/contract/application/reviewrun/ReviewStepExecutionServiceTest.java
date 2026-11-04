package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewStepExecutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser(
            "user-1", "auditor", "Audit User", Set.of("AUDITOR"));
    private final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
    private final AuthContext auth = mock(AuthContext.class);
    private final ReviewStepStartTransactionCoordinator starts = mock(ReviewStepStartTransactionCoordinator.class);
    private final FrozenReviewInputLoader inputs = mock(FrozenReviewInputLoader.class);
    private final ReviewStepCompleteTransactionCoordinator completes = mock(ReviewStepCompleteTransactionCoordinator.class);
    private final ReviewStepFailureTransactionCoordinator failures = mock(ReviewStepFailureTransactionCoordinator.class);
    private final ReviewStepUnknownTransactionCoordinator unknowns = mock(ReviewStepUnknownTransactionCoordinator.class);
    private final ReviewStepExternalRequestTransactionCoordinator external = mock(ReviewStepExternalRequestTransactionCoordinator.class);
    private final ReviewStepOutputReadService outputs = mock(ReviewStepOutputReadService.class);
    private final FrozenF02ResultMapper f02Mapper = mock(FrozenF02ResultMapper.class);
    private final ContractReviewStepRepository steps = mock(ContractReviewStepRepository.class);
    private final ContractReviewStepAttemptRepository attempts = mock(ContractReviewStepAttemptRepository.class);
    private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    private ContractReviewRun run;
    private ContractReviewStep step;
    private ContractReviewStepAttempt attempt;
    private ReviewStepStartTransactionCoordinator.StartedExecution started;
    private ReviewStepOutputReadService.StepOutput output;

    @BeforeEach
    void setUp() {
        when(auth.currentUser()).thenReturn(Optional.of(ACTOR));
        UUID runId = UUID.randomUUID(), stepId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        String canonical = "{}";
        run = new ContractReviewRun(runId, projectId, contractId, 0, null, null, 1,
                canonical, ContractSnapshotCanonicalizer.sha256(canonical), "profile-v1", "rule-v1",
                "model-v1", "a".repeat(64), ACTOR.userId(), ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1);
        step = new ContractReviewStep(stepId, runId, ContractReviewStepType.BREACH, true,
                ContractReviewStepStatus.RUNNING, "breach-v1", 1, null, null, null, NOW, null, 1);
        attempt = new ContractReviewStepAttempt(attemptId, runId, stepId, 1, "b".repeat(64),
                ContractReviewStepStatus.RUNNING, null, null, NOW, ReviewStepExternalCallState.NOT_STARTED,
                null, null, null, null, NOW, null, null, null, null, 1);
        var command = command("execute-key");
        var record = record(command, IdempotencyStatus.IN_PROGRESS, "CONTRACT_REVIEW_STEP_ATTEMPT", attemptId);
        started = new ReviewStepStartTransactionCoordinator.StartedExecution(
                run, step, attempt, record, ACTOR, command, List.of());
        output = new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt),
                null, List.of(), List.of(), List.of());
    }

    @Test
    void newCommandComputesOutsideCoordinatorsAndReturnsPersistedOutput() {
        var executor = mock(ReviewStepExecutor.class);
        when(executor.stepType()).thenReturn(ContractReviewStepType.BREACH);
        var service = service(List.of(executor));
        var command = command("new-key");
        when(starts.start(command, ACTOR)).thenReturn(started);
        var input = input();
        when(inputs.load(projectId, contractId, run.runId())).thenReturn(input);
        var computed = new StepComputationResult("REVIEW_FINDING_SET", attempt.attemptId(),
                List.of(), List.of(), null, null, null);
        when(executor.execute(any(), any())).thenReturn(computed);
        when(outputs.getStep(projectId, contractId, run.runId(), step.stepId())).thenReturn(output);

        var result = service.execute(command);

        assertThat(result.replayed()).isFalse();
        assertThat(result.output()).isEqualTo(output);
        verify(executor).execute(any(FrozenReviewInput.class), any(ReviewExecutionContext.class));
        verify(completes).complete(started, computed);
    }

    @Test
    void completedSameKeyReopensDurableOutputWithoutStartLoaderOrExecutor() {
        step = completedStep();
        attempt = completedAttempt();
        output = new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt),
                null, List.of(), List.of(), List.of());
        var command = command("replay-key");
        String fingerprint = ReviewStepStartTransactionCoordinator.fingerprint(ACTOR, run, step, attempt);
        idempotency.claim(request(command, fingerprint));
        var inProgress = idempotency.findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, command.idempotencyKey()).orElseThrow();
        idempotency.markSucceeded(inProgress, "REVIEW_FINDING_SET", attempt.attemptId().toString(), NOW);
        when(steps.findStepById(step.stepId())).thenReturn(Optional.of(step));
        when(attempts.findAttemptsByRunId(run.runId())).thenReturn(List.of(attempt));
        when(outputs.getStep(projectId, contractId, run.runId(), step.stepId())).thenReturn(output);
        var executor = mock(ReviewStepExecutor.class);

        var result = service(List.of()).execute(command);

        assertThat(result.replayed()).isTrue();
        assertThat(result.output()).isEqualTo(output);
        verify(starts, never()).start(any(), any());
        verify(inputs, never()).load(any(), any(), any());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void sameKeyDifferentActorConflictsBeforeAnyEngineCall() {
        var command = command("actor-conflict");
        idempotency.claim(new com.fuquan.audit.common.application.port.IdempotencyRequest(
                ReviewStepStartTransactionCoordinator.SCOPE, command.idempotencyKey(), "other-user",
                "CONTRACT_REVIEW_STEP", step.stepId().toString(), "c".repeat(64),
                "CONTRACT_REVIEW_STEP_ATTEMPT", attempt.attemptId().toString(),
                "old-request", "old-trace", NOW));
        var executor = mock(ReviewStepExecutor.class);
        assertThatThrownBy(() -> service(List.of()).execute(command))
                .isInstanceOf(BusinessException.class);
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void interruptedF02CompleteReopensSucceededInternalTaskAndFinishesSameAttempt() {
        UUID taskId = UUID.randomUUID();
        step = new ContractReviewStep(step.stepId(), step.runId(),
                ContractReviewStepType.F02_DETERMINISTIC, true,
                ContractReviewStepStatus.RUNNING, run.ruleVersion(), 1,
                null, null, null, NOW, null, 1);
        attempt = new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(),
                attempt.stepId(), 1, attempt.operationIdentity(), ContractReviewStepStatus.RUNNING,
                null, null, NOW, ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
        var command = command("f02-interrupted-complete");
        String fingerprint = ReviewStepStartTransactionCoordinator.fingerprint(ACTOR, run, step, attempt);
        idempotency.claim(request(command, fingerprint));
        String internalKey = FrozenF02ReviewStepExecutor.internalKey(
                run.runId(), step.stepId(), attempt.attemptId());
        var f02Claim = idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, internalKey, ACTOR.userId(),
                "CONTRACT", contractId.toString(), "f".repeat(64),
                "AUDIT_TASK", taskId.toString(), "f02-request", "f02-trace", NOW));
        idempotency.markSucceeded(f02Claim.record(), "AUDIT_TASK", taskId.toString(), NOW);
        var runningOutput = new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt),
                null, List.of(), List.of(), List.of());
        var completedStep = new ContractReviewStep(step.stepId(), step.runId(), step.stepType(), true,
                ContractReviewStepStatus.COMPLETED, step.stepPolicyVersion(), 1,
                FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId, null, NOW, NOW, 2);
        var completedAttempt = new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(),
                attempt.stepId(), 1, attempt.operationIdentity(), ContractReviewStepStatus.COMPLETED,
                null, null, NOW, ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, NOW, null, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId, 2);
        var completedOutput = new ReviewStepOutputReadService.StepOutput(run, completedStep,
                List.of(completedAttempt), mock(com.fuquan.audit.review.domain.AuditTask.class),
                List.of(mock(com.fuquan.audit.review.domain.AuditCheckResult.class)), List.of(), List.of());
        when(outputs.getStep(projectId, contractId, run.runId(), step.stepId()))
                .thenReturn(runningOutput, completedOutput);
        var durable = new ReviewStepOutputReadService.F02Output(
                mock(com.fuquan.audit.review.domain.AuditTask.class),
                List.of(mock(com.fuquan.audit.review.domain.AuditCheckResult.class)));
        when(outputs.validateF02Output(run, step, attempt, taskId, ACTOR.userId())).thenReturn(durable);
        FrozenReviewInput frozen = input();
        when(inputs.load(projectId, contractId, run.runId())).thenReturn(frozen);
        when(f02Mapper.map(org.mockito.ArgumentMatchers.eq(frozen), any(),
                org.mockito.ArgumentMatchers.eq(taskId), org.mockito.ArgumentMatchers.eq(durable.checks())))
                .thenReturn(new StepComputationResult(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE,
                        taskId, List.of(), List.of(), null, null, null));

        var result = service(List.of()).execute(command);

        assertThat(result.replayed()).isTrue();
        assertThat(result.output()).isEqualTo(completedOutput);
        verify(outputs).validateF02Output(run, step, attempt, taskId, ACTOR.userId());
        verify(completes).complete(any(), org.mockito.ArgumentMatchers.argThat(value ->
                FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(value.outputResourceType())
                        && taskId.equals(value.outputResourceId())
                        && value.findings().isEmpty() && value.evidence().isEmpty()));
        verify(starts, never()).start(any(), any());
        verify(inputs).load(projectId, contractId, run.runId());
    }

    @Test
    void workerLeasedF02AttemptCannotBeCompletedThroughLegacySynchronousReplay() {
        UUID taskId = UUID.randomUUID();
        step = new ContractReviewStep(step.stepId(), step.runId(),
                ContractReviewStepType.F02_DETERMINISTIC, true,
                ContractReviewStepStatus.RUNNING, run.ruleVersion(), 1,
                null, null, null, NOW, null, 1);
        attempt = new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(),
                attempt.stepId(), 1, attempt.operationIdentity(), ContractReviewStepStatus.RUNNING,
                "worker-1", NOW.plusSeconds(60), NOW, ReviewStepExternalCallState.NOT_STARTED,
                null, null, null, null, NOW, null, null, null, null, 2);
        var command = command("worker-owned-f02");
        String fingerprint = ReviewStepStartTransactionCoordinator.fingerprint(ACTOR, run, step, attempt);
        idempotency.claim(request(command, fingerprint));
        String internalKey = FrozenF02ReviewStepExecutor.internalKey(
                run.runId(), step.stepId(), attempt.attemptId());
        var f02Claim = idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, internalKey, ACTOR.userId(),
                "CONTRACT", contractId.toString(), "f".repeat(64),
                "AUDIT_TASK", taskId.toString(), "f02-request", "f02-trace", NOW));
        idempotency.markSucceeded(f02Claim.record(), "AUDIT_TASK", taskId.toString(), NOW);
        when(outputs.getStep(projectId, contractId, run.runId(), step.stepId())).thenReturn(
                new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt),
                        null, List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service(List.of()).execute(command))
                .isInstanceOf(BusinessException.class);

        verify(completes, never()).complete(any(), any());
        verify(starts, never()).start(any(), any());
    }

    @Test
    void provenFailureAndUnknownUseDifferentIndependentClosurePaths() {
        var provenExecutor = executorThrowing(new ReviewStepProvenFailureException(
                "SEMANTIC_UNAVAILABLE", new IllegalStateException("unavailable")));
        var provenCommand = command("proven-key");
        when(starts.start(provenCommand, ACTOR)).thenReturn(started);
        when(inputs.load(projectId, contractId, run.runId())).thenReturn(input());
        assertThatThrownBy(() -> service(List.of(provenExecutor)).execute(provenCommand))
                .isInstanceOf(ReviewStepProvenFailureException.class);
        verify(failures).fail(started, "SEMANTIC_UNAVAILABLE");
        verify(unknowns, never()).unknown(any(), any());

        var uncertainExecutor = executorThrowing(new ReviewStepOutcomeUnknownException(
                "SEMANTIC_OUTCOME_UNKNOWN", new IllegalStateException("timeout")));
        var unknownCommand = command("unknown-key");
        when(starts.start(unknownCommand, ACTOR)).thenReturn(started);
        assertThatThrownBy(() -> service(List.of(uncertainExecutor)).execute(unknownCommand))
                .isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(unknowns).unknown(started, "SEMANTIC_OUTCOME_UNKNOWN");
    }

    @Test
    void duplicateExecutorTypeFailsFastAndServiceOwnsNoTransaction() {
        var one = mock(ReviewStepExecutor.class);
        var two = mock(ReviewStepExecutor.class);
        when(one.stepType()).thenReturn(ContractReviewStepType.BREACH);
        when(two.stepType()).thenReturn(ContractReviewStepType.BREACH);
        assertThatThrownBy(() -> service(List.of(one, two))).isInstanceOf(IllegalStateException.class);
        assertThat(ReviewStepExecutionService.class.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class)).isNull();
        assertThat(java.util.Arrays.stream(ReviewStepExecutionService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("execute"))
                .allMatch(method -> method.getAnnotation(
                        org.springframework.transaction.annotation.Transactional.class) == null)).isTrue();
    }

    private ReviewStepExecutionService service(List<ReviewStepExecutor> executors) {
        return new ReviewStepExecutionService(auth, idempotency, steps, attempts, starts, inputs,
                completes, failures, unknowns, external, outputs, f02Mapper, executors);
    }

    private ReviewStepExecutor executorThrowing(RuntimeException failure) {
        return new ReviewStepExecutor() {
            @Override public ContractReviewStepType stepType() { return ContractReviewStepType.BREACH; }
            @Override public StepComputationResult execute(FrozenReviewInput input,
                    ReviewExecutionContext context) { throw failure; }
        };
    }

    private ReviewStepStartTransactionCoordinator.StartCommand command(String key) {
        return new ReviewStepStartTransactionCoordinator.StartCommand(projectId, contractId,
                run.runId(), step.stepId(), key,
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
    }

    private com.fuquan.audit.common.application.port.IdempotencyRequest request(
            ReviewStepStartTransactionCoordinator.StartCommand command, String fingerprint) {
        return new com.fuquan.audit.common.application.port.IdempotencyRequest(
                ReviewStepStartTransactionCoordinator.SCOPE, command.idempotencyKey(), ACTOR.userId(),
                "CONTRACT_REVIEW_STEP", step.stepId().toString(), fingerprint,
                "CONTRACT_REVIEW_STEP_ATTEMPT", attempt.attemptId().toString(),
                command.metadata().requestId(), command.metadata().traceId(), NOW);
    }

    private IdempotencyRecord record(ReviewStepStartTransactionCoordinator.StartCommand command,
            IdempotencyStatus status, String resultType, UUID resultId) {
        return new IdempotencyRecord(UUID.randomUUID(), ReviewStepStartTransactionCoordinator.SCOPE,
                command.idempotencyKey(), ACTOR.userId(), "CONTRACT_REVIEW_STEP", step.stepId().toString(),
                ReviewStepStartTransactionCoordinator.fingerprint(ACTOR, run, step, attempt), status,
                resultType, resultId.toString(), command.metadata().requestId(), command.metadata().traceId(),
                NOW, status == IdempotencyStatus.IN_PROGRESS ? null : NOW, 0);
    }

    private FrozenReviewInput input() {
        var contract = new Contract(contractId, "C-1", "Synthetic Contract", "PROCUREMENT",
                "Party A", "Party B", null, Money.of(new BigDecimal("100.00")), null,
                "ACTIVE", 0, NOW, NOW, List.of(), null);
        return new FrozenReviewInput(run, contract, List.of());
    }

    private ContractReviewStep completedStep() {
        return new ContractReviewStep(step.stepId(), step.runId(), step.stepType(), step.required(),
                ContractReviewStepStatus.COMPLETED, step.stepPolicyVersion(), 1,
                "REVIEW_FINDING_SET", attempt.attemptId(), null, NOW, NOW, 2);
    }

    private ContractReviewStepAttempt completedAttempt() {
        return new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(), attempt.stepId(), 1,
                attempt.operationIdentity(), ContractReviewStepStatus.COMPLETED, null, null, NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, NOW, NOW, null,
                "REVIEW_FINDING_SET", attempt.attemptId(), 2);
    }
}
