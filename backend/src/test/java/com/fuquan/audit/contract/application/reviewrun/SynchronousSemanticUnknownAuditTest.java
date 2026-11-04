package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.contract.application.ai.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.reviewrun.executor.*;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real synchronous start, executor and UNKNOWN closure; only repositories/external model are local doubles. */
class SynchronousSemanticUnknownAuditTest {
    private static final Clock CLOCK = Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC);

    // Catches the synchronous service dropping known calls when shared executor classifies timeout as UNKNOWN.
    @Test void synchronousUnknownPersistsObservedPrefixAndRetainsOriginalOperation() {
        var f = fixture();
        var actor = new CurrentUser("initiator-1", "auditor", "Local Test", Set.of());
        var starts = new ReviewStepStartTransactionCoordinator(f.runs, f.steps, f.attempts,
                f.idempotency, f.events::add, f.policies(), CLOCK);
        var unknowns = new ReviewStepUnknownTransactionCoordinator(f.runs, f.steps, f.attempts,
                f.events::add, new ReviewRunStatusAggregator(), CLOCK, f.modelCalls);
        ContractTextAnalysisPort port = request -> {
            throw new ExternalServiceException(ExternalServiceType.AI, new ContractTextAnalysisFailure(
                    ContractTextAnalysisFailure.Reason.MODEL_GATEWAY_ERROR, List.of(
                    new ContractTextAnalysisResult.ModelCall(1, ContractTextAnalysisResult.CallPhase.TRANSPORT,
                            ContractTextAnalysisResult.CallOutcome.SUCCEEDED, null, 5, "STOP", 8, 4, 12,
                            "OPENAI_COMPATIBLE", "synthetic-model"),
                    new ContractTextAnalysisResult.ModelCall(2, ContractTextAnalysisResult.CallPhase.TRANSPORT,
                            ContractTextAnalysisResult.CallOutcome.FAILED, "READ_TIMEOUT", 7, null, null, null, null,
                            "OPENAI_COMPATIBLE", "synthetic-model"))));
        };
        var executor = new FrozenSemanticReviewStepExecutor(port,
                new FrozenSemanticRequestBuilder(new ContractAiTextRedactor()), new FrozenEvidenceGrounder(), CLOCK);
        var service = new ReviewStepExecutionService(() -> Optional.of(actor), f.idempotency, f.steps, f.attempts,
                starts, f.inputs, f.completes, mock(ReviewStepFailureTransactionCoordinator.class), unknowns,
                new ReviewStepExternalRequestTransactionCoordinator(f.attempts, CLOCK), f.outputs,
                mock(FrozenF02ResultMapper.class), List.of(executor));
        var command = new ReviewStepStartTransactionCoordinator.StartCommand(f.run.get().projectId(),
                f.run.get().contractId(), f.run.get().runId(), f.step.get().stepId(), "sync-original-key",
                new ProjectRequestMetadata("synthetic-request", "synthetic-trace", null));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(ReviewStepOutcomeUnknownException.class);

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo("SEMANTIC_MODEL_GATEWAY_ERROR");
        assertThat(f.attempt.get().providerRequestId()).isNull();
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                command.idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        var persisted = f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(1).safeReason()).isEqualTo("READ_TIMEOUT");
    }

    // Catches foreign call ownership, stale attempt identity, or worker leases entering synchronous closure.
    @ParameterizedTest
    @ValueSource(strings = {"attempt", "attemptNo", "fingerprint", "ordinal", "callId", "operation", "actor", "lease", "leaseUntil", "currentNo"})
    void invalidIdentityNeverChangesStateOrPublishesObservedCalls(String mutation) {
        var f = fixture();
        var started = requested(f);
        var calls = new ArrayList<ContractModelCallAudit>();
        var first = WorkerSemanticFailureAuditTest.call(mutation.equals("attempt") ? UUID.randomUUID() : started.attempt().attemptId(),
                mutation.equals("attemptNo") ? 2 : 1,
                mutation.equals("fingerprint") ? "e".repeat(64) : WorkerSemanticFailureAuditTest.FINGERPRINT, 1);
        calls.add(first);
        if (mutation.equals("ordinal")) calls.add(WorkerSemanticFailureAuditTest.call(started.attempt().attemptId(), 1,
                WorkerSemanticFailureAuditTest.FINGERPRINT, 1));
        if (mutation.equals("callId")) calls.add(new ContractModelCallAudit(first.callAuditId(), null,
                started.attempt().attemptId(), 1, null, 2, first.phase(), first.requestFingerprint(),
                first.provider(), first.modelName(), first.outcome(), first.safeReason(), first.durationMs(),
                null, null, null, null, null, first.createdAt()));
        if (mutation.equals("operation")) started = new ReviewStepStartTransactionCoordinator.StartedExecution(
                started.run(), started.step(), copy(started.attempt(), "e".repeat(64), null, null),
                started.idempotency(), started.actor(), started.command(), List.of());
        if (mutation.equals("actor")) started = new ReviewStepStartTransactionCoordinator.StartedExecution(
                started.run(), started.step(), started.attempt(), started.idempotency(),
                new CurrentUser("foreign-actor", "auditor", "Local Test", Set.of()), started.command(), List.of());
        if (mutation.equals("lease") || mutation.equals("leaseUntil")) f.attempt.set(copy(f.attempt.get(),
                f.attempt.get().operationIdentity(), mutation.equals("lease") ? "worker-a" : null,
                mutation.equals("leaseUntil") ? CLOCK.instant().plusSeconds(60) : null));
        if (mutation.equals("currentNo")) {
            var s = f.step.get();
            f.step.set(new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), s.required(), s.status(),
                    s.stepPolicyVersion(), 2, null, null, null, s.startedAt(), null, s.versionNo()));
        }
        var execution = started;
        assertThatThrownBy(() -> unknowns(f).unknown(execution, "SEMANTIC_MODEL_GATEWAY_ERROR", calls))
                .isInstanceOf(BusinessException.class);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(f.step.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).isEmpty();
    }

    @Test void unknownWithoutObservedCallsDoesNotInventAuditAndDuplicateClosureCannotPublishTwice() {
        var f = fixture();
        var started = requested(f);
        unknowns(f).unknown(started, "SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).isEmpty();
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                started.command().idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThatThrownBy(() -> unknowns(f).unknown(started, "SEMANTIC_OUTCOME_UNKNOWN", List.of(
                WorkerSemanticFailureAuditTest.call(started.attempt().attemptId(), 1,
                        WorkerSemanticFailureAuditTest.FINGERPRINT, 1)))) .isInstanceOf(BusinessException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).isEmpty();
    }

    @Test void duplicateObservedClosurePublishesOnceAndLosingTransitionNeverPublishes() {
        var f = fixture();
        var started = requested(f);
        var calls = List.of(WorkerSemanticFailureAuditTest.call(started.attempt().attemptId(), 1,
                WorkerSemanticFailureAuditTest.FINGERPRINT, 1));
        unknowns(f).unknown(started, "SEMANTIC_MODEL_GATEWAY_ERROR", calls);
        assertThatThrownBy(() -> unknowns(f).unknown(started, "SEMANTIC_MODEL_GATEWAY_ERROR", calls))
                .isInstanceOf(BusinessException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).hasSize(1);

        var losing = fixture();
        var stale = requested(losing);
        doThrow(ReviewWorkerLeasePolicy.conflict()).when(losing.attempts).transition(any(), any());
        assertThatThrownBy(() -> unknowns(losing).unknown(stale, "SEMANTIC_MODEL_GATEWAY_ERROR", List.of(
                WorkerSemanticFailureAuditTest.call(stale.attempt().attemptId(), 1,
                        WorkerSemanticFailureAuditTest.FINGERPRINT, 1)))) .isInstanceOf(BusinessException.class);
        assertThat(losing.modelCalls.findByReviewStepAttempt(stale.attempt().attemptId())).isEmpty();
        assertThat(losing.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    private static ReviewStepUnknownTransactionCoordinator unknowns(ReviewWorkerTransactionCoordinatorTest.Fixture f) {
        return new ReviewStepUnknownTransactionCoordinator(f.runs, f.steps, f.attempts, f.events::add,
                new ReviewRunStatusAggregator(), CLOCK, f.modelCalls);
    }

    private static ReviewStepStartTransactionCoordinator.StartedExecution requested(ReviewWorkerTransactionCoordinatorTest.Fixture f) {
        var actor = new CurrentUser("initiator-1", "auditor", "Local Test", Set.of());
        var command = new ReviewStepStartTransactionCoordinator.StartCommand(f.run.get().projectId(),
                f.run.get().contractId(), f.run.get().runId(), f.step.get().stepId(), "sync-original-key",
                new ProjectRequestMetadata("synthetic-request", "synthetic-trace", null));
        var started = new ReviewStepStartTransactionCoordinator(f.runs, f.steps, f.attempts, f.idempotency,
                f.events::add, f.policies(), CLOCK).start(command, actor);
        new ReviewStepExternalRequestTransactionCoordinator(f.attempts, CLOCK)
                .markRequested(started, WorkerSemanticFailureAuditTest.FINGERPRINT);
        return started;
    }

    private static ContractReviewStepAttempt copy(ContractReviewStepAttempt a, String identity, String owner, Instant until) {
        return new ContractReviewStepAttempt(a.attemptId(), a.runId(), a.stepId(), a.attemptNo(), identity,
                a.status(), owner, until, a.claimedAt(), a.externalCallState(), a.externalRequestFingerprint(),
                a.providerRequestId(), a.provider(), a.model(), a.startedAt(), a.completedAt(), a.safeReasonCode(),
                a.outputResourceType(), a.outputResourceId(), a.versionNo());
    }

    private static ReviewWorkerTransactionCoordinatorTest.Fixture fixture() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        when(f.attempts.findAttemptsByRunId(any())).thenAnswer(call -> List.of(f.attempt.get()));
        String text = "Synthetic delivery and acceptance clause.";
        var material = new FrozenReviewMaterial(UUID.randomUUID(), 1, UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), 1, ContractSnapshotCanonicalizer.sha256(text), text,
                DocumentRole.CONTRACT_BODY, null, null, null);
        when(f.inputs.load(any(), any(), any())).thenAnswer(call -> new FrozenReviewInput(f.run.get(),
                com.fuquan.audit.contract.infra.memory.LocalContractFixtures.contracts().get(0), List.of(material)));
        return f;
    }
}
