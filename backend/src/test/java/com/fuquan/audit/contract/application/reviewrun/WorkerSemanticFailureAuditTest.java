package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.ai.*;
import com.fuquan.audit.contract.application.reviewrun.executor.*;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.net.SocketTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import com.fuquan.audit.integration.ai.fastapi.FastApiAiReviewAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerSemanticFailureAuditTest {
    static final String FINGERPRINT = "d".repeat(64);

    @ParameterizedTest
    @ValueSource(strings = {"TRANSPORT_OUTCOME_UNKNOWN", "READ_TIMEOUT", "TOTAL_BUDGET_EXCEEDED", "MODEL_TOTAL_BUDGET_EXHAUSTED", "PROVIDER_5XX"})
    void typedGatewayFailuresKeepUncertainCallOutcomeUnknownAndObservedProviderFailureFailed(String callReason) {
        var f = semanticFixture();
        String text = "synthetic frozen clause";
        var material = new FrozenReviewMaterial(UUID.randomUUID(), 1, UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), 1, ContractSnapshotCanonicalizer.sha256(text), text,
                DocumentRole.CONTRACT_BODY, null, null, null);
        when(f.inputs.load(any(), any(), any())).thenAnswer(invocation -> new FrozenReviewInput(f.run.get(),
                com.fuquan.audit.contract.infra.memory.LocalContractFixtures.contracts().get(0), List.of(material)));
        var builder = RestClient.builder().baseUrl("http://synthetic.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        boolean knownPreDispatchBudget = Set.of("TOTAL_BUDGET_EXCEEDED", "MODEL_TOTAL_BUDGET_EXHAUSTED").contains(callReason);
        String code = knownPreDispatchBudget
                ? "MODEL_TOTAL_BUDGET_EXHAUSTED" : "MODEL_GATEWAY_ERROR";
        server.expect(requestTo("http://synthetic.invalid/ai/v1/agent/contract-text-analysis"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"%s","message":"safe fixture","data":{"modelCalls":[
                        {"ordinal":1,"phase":"TRANSPORT","outcome":"SUCCEEDED","durationMs":5,
                         "provider":"OPENAI_COMPATIBLE","model":"qwen-test"},
                        {"ordinal":2,"phase":"TRANSPORT","outcome":"FAILED","safeReason":"%s","durationMs":7,
                         "provider":"OPENAI_COMPATIBLE","model":"qwen-test"}]}}
                        """.formatted(code, callReason)));
        ContractTextAnalysisPort port = new FastApiAiReviewAdapter(builder.build());
        var executor = new FrozenSemanticReviewStepExecutor(port,
                new FrozenSemanticRequestBuilder(new ContractAiTextRedactor()), new FrozenEvidenceGrounder(),
                Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC));
        ContractReviewWorkerTest.configureDiscovery(f);
        try (var worker = ContractReviewWorkerTest.timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        boolean provenProviderResponse = callReason.equals("PROVIDER_5XX") || knownPreDispatchBudget;
        assertThat(f.attempt.get().status()).isEqualTo(provenProviderResponse
                ? ContractReviewStepStatus.FAILED : ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo(knownPreDispatchBudget
                ? "SEMANTIC_TOTAL_BUDGET_EXHAUSTED" : "SEMANTIC_MODEL_GATEWAY_ERROR");
        var persisted = f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).outcome()).isEqualTo(CallOutcome.SUCCEEDED);
        assertThat(persisted.get(1).safeReason()).isEqualTo(callReason);
        assertThat(f.attempt.get().providerRequestId()).isNull();
        server.verify();
    }

    // Catches known upstream calls being discarded by local validation or uncertain transport closure.
    @ParameterizedTest
    @ValueSource(strings = {"foreignReference", "unavailable", "timeoutWithObservedPrefix", "timeoutNotObserved"})
    void actualSemanticExecutorRetainsEveryObservedCallAcrossRejectedOrUnknownOutcomes(String scenario) {
        var f = semanticFixture();
        String text = "synthetic frozen clause";
        var material = new FrozenReviewMaterial(UUID.randomUUID(), 1, UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), 1, ContractSnapshotCanonicalizer.sha256(text), text,
                DocumentRole.CONTRACT_BODY, null, null, null);
        when(f.inputs.load(any(), any(), any())).thenAnswer(invocation -> new FrozenReviewInput(f.run.get(),
                com.fuquan.audit.contract.infra.memory.LocalContractFixtures.contracts().get(0), List.of(material)));
        var observed = new ContractTextAnalysisResult.ModelCall(1, ContractTextAnalysisResult.CallPhase.TRANSPORT,
                ContractTextAnalysisResult.CallOutcome.SUCCEEDED, null, 7, "STOP", 10, 5, 15,
                "OPENAI_COMPATIBLE", "qwen-test");
        ContractTextAnalysisPort port = request -> {
            if (scenario.startsWith("timeout")) {
                var timeout = new SocketTimeoutException("synthetic timeout");
                if (scenario.equals("timeoutWithObservedPrefix")) timeout.initCause(new ContractTextAnalysisFailure(
                        ContractTextAnalysisFailure.Reason.MODEL_GATEWAY_ERROR, List.of(observed)));
                throw new ExternalServiceException(ExternalServiceType.AI, new ResourceAccessException("synthetic", timeout));
            }
            var hints = scenario.equals("foreignReference") ? List.of(new ContractTextAnalysisResult.Hint(
                    ContractTextAnalysisResult.SemanticCategory.OTHER_TEXT,
                    ContractTextAnalysisResult.Attention.REVIEW, "Synthetic title", "Synthetic description",
                    List.of(UUID.randomUUID()))) : List.<ContractTextAnalysisResult.Hint>of();
            return new ContractTextAnalysisResult(!scenario.equals("unavailable"), List.of(), hints,
                    "qwen-test", 7, List.of(observed));
        };
        var executor = new FrozenSemanticReviewStepExecutor(port,
                new FrozenSemanticRequestBuilder(new ContractAiTextRedactor()), new FrozenEvidenceGrounder(),
                Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC));
        ContractReviewWorkerTest.configureDiscovery(f);
        try (var worker = ContractReviewWorkerTest.timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        var status = scenario.startsWith("timeout") ? ContractReviewStepStatus.UNKNOWN : ContractReviewStepStatus.FAILED;
        String reason = scenario.startsWith("timeout") ? "SEMANTIC_OUTCOME_UNKNOWN"
                : scenario.equals("unavailable") ? "SEMANTIC_UNAVAILABLE" : "SEMANTIC_RESPONSE_INVALID";
        assertThat(f.attempt.get().status()).isEqualTo(status);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo(reason);
        assertThat(f.attempt.get().providerRequestId()).isNull();
        var persisted = f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId());
        if (scenario.equals("timeoutNotObserved")) assertThat(persisted).isEmpty();
        else assertThat(persisted).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(call.requestFingerprint()).isEqualTo(f.attempt.get().externalRequestFingerprint());
            assertThat(call.totalTokens()).isEqualTo(15);
        });
    }

    // Catches the worker dropping the proven failure's model calls before durable closure.
    @Test void workerPersistsProvenFailureCallsWithSafeReasonAndNoFabricatedProviderId() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        ContractReviewWorkerTest.configureDiscovery(f);
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.SEMANTIC_AI; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                context.markExternalRequested(FINGERPRINT);
                throw new ReviewStepProvenFailureException("SEMANTIC_MODEL_INVALID_RESULT",
                        new IllegalStateException("safe fixture"), List.of(call(
                                context.startedAttempt().attemptId(), 1, FINGERPRINT, 1)));
            }
        };
        try (var worker = ContractReviewWorkerTest.timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.FAILED);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo("SEMANTIC_MODEL_INVALID_RESULT");
        assertThat(f.attempt.get().providerRequestId()).isNull();
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId()))
                .singleElement().satisfies(call -> {
                    assertThat(call.phase()).isEqualTo(CallPhase.REPAIR);
                    assertThat(call.outcome()).isEqualTo(CallOutcome.FAILED);
                    assertThat(call.safeReason()).isEqualTo("INVALID_PROVIDER_JSON");
                    assertThat(call.durationMs()).isEqualTo(11);
                    assertThat(call.requestFingerprint()).isEqualTo(FINGERPRINT);
                });
    }

    // Catches double publication from duplicate delivery of one failure outcome.
    @Test void sameAttemptFailureCanOnlyPublishEachCallOnce() {
        var f = semanticFixture();
        var execution = requested(f);
        var calls = List.of(call(execution.attempt().attemptId(), 1, FINGERPRINT, 1),
                call(execution.attempt().attemptId(), 1, FINGERPRINT, 2));
        f.coordinator.close(execution, ContractReviewStepStatus.FAILED, "SEMANTIC_MODEL_INVALID_RESULT", calls);
        assertThatThrownBy(() -> f.coordinator.close(execution, ContractReviewStepStatus.FAILED,
                "SEMANTIC_MODEL_INVALID_RESULT", calls)).isInstanceOf(BusinessException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(execution.attempt().attemptId())).hasSize(2);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.COMPLETED);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                execution.command().idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test void concurrentDuplicateFailureClosuresPublishOnlyTheWinningCalls() throws Exception {
        var f = semanticFixture();
        var execution = requested(f);
        var ready = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> close = () -> {
                ready.await();
                try {
                    f.coordinator.close(execution, ContractReviewStepStatus.FAILED, "SEMANTIC_MODEL_INVALID_RESULT",
                            List.of(call(execution.attempt().attemptId(), 1, FINGERPRINT, 1)));
                    return true;
                } catch (BusinessException lostLease) { return false; }
            };
            var first = pool.submit(close);
            var second = pool.submit(close);
            ready.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(f.modelCalls.findByReviewStepAttempt(execution.attempt().attemptId())).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    // Each mutation represents an incorrectly attributed durable audit, rejected before any write.
    @ParameterizedTest
    @ValueSource(strings = {"attempt", "attemptNo", "fingerprint", "ordinal", "callId", "operationIdentity"})
    void rejectsForeignOrDuplicateCallIdentityBeforeChangingDurableState(String mutation) {
        var f = semanticFixture();
        var execution = requested(f);
        var first = call(mutation.equals("attempt") ? UUID.randomUUID() : execution.attempt().attemptId(),
                mutation.equals("attemptNo") ? 2 : 1,
                mutation.equals("fingerprint") ? "e".repeat(64) : FINGERPRINT, 1);
        var calls = new ArrayList<>(List.of(first));
        if (mutation.equals("ordinal")) calls.add(call(execution.attempt().attemptId(), 1, FINGERPRINT, 1));
        if (mutation.equals("callId")) calls.add(new ContractModelCallAudit(first.callAuditId(), null,
                execution.attempt().attemptId(), 1, null, 2, CallPhase.REPAIR, FINGERPRINT,
                "OPENAI_COMPATIBLE", "qwen-test", CallOutcome.FAILED, "INVALID_PROVIDER_JSON",
                11, null, null, null, null, null, ReviewWorkerTransactionCoordinatorTest.NOW));
        if (mutation.equals("operationIdentity")) {
            var a = execution.attempt();
            var forged = new ContractReviewStepAttempt(a.attemptId(), a.runId(), a.stepId(), a.attemptNo(),
                    "f".repeat(64), a.status(), a.leaseOwner(), a.leaseUntil(), a.claimedAt(),
                    a.externalCallState(), a.externalRequestFingerprint(), a.providerRequestId(), a.provider(),
                    a.model(), a.startedAt(), a.completedAt(), a.safeReasonCode(), a.outputResourceType(),
                    a.outputResourceId(), a.versionNo());
            execution = new ReviewStepStartTransactionCoordinator.StartedExecution(execution.run(), execution.step(),
                    forged, execution.idempotency(), execution.actor(), execution.command(), execution.f02Configurations());
        }
        var observed = execution;
        var beforeAttempt = f.attempt.get();
        var beforeStep = f.step.get();
        var beforeRun = f.run.get();
        int beforeEvents = f.events.size();
        assertThatThrownBy(() -> f.coordinator.close(observed, ContractReviewStepStatus.FAILED,
                "SEMANTIC_MODEL_INVALID_RESULT", calls)).isInstanceOf(BusinessException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(beforeAttempt.attemptId())).isEmpty();
        assertThat(f.attempt.get()).isEqualTo(beforeAttempt);
        assertThat(f.step.get()).isEqualTo(beforeStep);
        assertThat(f.run.get()).isEqualTo(beforeRun);
        assertThat(f.events).hasSize(beforeEvents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "expiry", "cas"})
    void staleOrLostLeaseNeverWritesFailureAudits(String fenceFailure) {
        var f = semanticFixture();
        var execution = requested(f);
        if (fenceFailure.equals("heartbeat")) {
            f.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(1));
            f.coordinator.heartbeat(execution);
        }
        if (fenceFailure.equals("expiry")) f.now.set(execution.attempt().leaseUntil());
        if (fenceFailure.equals("cas")) doThrow(ReviewWorkerLeasePolicy.conflict())
                .when(f.workers).compareAndSetAttempt(any(), any(), any(), any());
        var before = f.attempt.get();
        int beforeEvents = f.events.size();
        assertThatThrownBy(() -> f.coordinator.close(execution, ContractReviewStepStatus.FAILED,
                "SEMANTIC_MODEL_INVALID_RESULT", List.of(call(execution.attempt().attemptId(), 1, FINGERPRINT, 1))))
                .isInstanceOf(BusinessException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(execution.attempt().attemptId())).isEmpty();
        assertThat(f.attempt.get()).isEqualTo(before);
        assertThat(f.events).hasSize(beforeEvents);
    }

    @Test void unknownClosureKeepsOperationInProgressWithoutInventingCalls() {
        var f = semanticFixture();
        var execution = requested(f);
        f.coordinator.close(execution, ContractReviewStepStatus.UNKNOWN, "SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.modelCalls.findByReviewStepAttempt(execution.attempt().attemptId())).isEmpty();
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
        assertThat(f.step.get().safeReasonCode()).isEqualTo("SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                execution.command().idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test void timedOutComputationCannotPublishLateFailureCallsAfterUnknownRecovery() throws Exception {
        var f = semanticFixture();
        ContractReviewWorkerTest.configureDiscovery(f);
        try (var executor = new ContractReviewWorkerTest.StubbornExecutor(ContractReviewStepType.SEMANTIC_AI,
                (input, context) -> { throw new ReviewStepProvenFailureException("SEMANTIC_MODEL_INVALID_RESULT",
                        new IllegalStateException("safe fixture"), List.of(call(
                                context.startedAttempt().attemptId(), 1, FINGERPRINT, 1))); });
             var worker = ContractReviewWorkerTest.timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            assertThat(executor.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).isEmpty();
            f.now.set(f.attempt.get().leaseUntil());
            worker.scanOnce(4);
            executor.release.countDown();
            assertThat(executor.finished.await(5, TimeUnit.SECONDS)).isTrue();
            worker.scanOnce(4);
            assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
            assertThat(f.attempt.get().safeReasonCode()).isEqualTo("WORKER_EXTERNAL_OUTCOME_UNPROVEN");
            assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).isEmpty();
            assertThat(executor.calls.get()).isEqualTo(1);
        }
    }

    @Test void successfulWorkerPublicationStillPersistsCallsWithCompletion() {
        var f = semanticFixture();
        var completion = new ReviewStepCompleteTransactionCoordinator(f.runs, f.steps, f.attempts,
                mock(ContractReviewRunMaterialRepository.class), mock(ContractReviewFindingRepository.class),
                mock(ContractReviewEvidenceRepository.class), null, f.idempotency, f.modelCalls,
                f.events::add, new ReviewRunStatusAggregator(), Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC));
        f.coordinator = f.coordinator(completion, f.outputs);
        ContractReviewWorkerTest.configureDiscovery(f);
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.SEMANTIC_AI; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                context.markExternalRequested(FINGERPRINT);
                var call = new ContractModelCallAudit(UUID.randomUUID(), null, context.startedAttempt().attemptId(),
                        1, null, 1, CallPhase.TRANSPORT, FINGERPRINT, "OPENAI_COMPATIBLE", "qwen-test",
                        CallOutcome.SUCCEEDED, null, 7, "STOP", 10, 5, 15, null,
                        ReviewWorkerTransactionCoordinatorTest.NOW);
                return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), List.of(), List.of(call), null, null, "qwen-test");
            }
        };
        try (var worker = ContractReviewWorkerTest.timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(f.attempt.get().providerRequestId()).isNull();
        assertThat(f.modelCalls.findByReviewStepAttempt(f.attempt.get().attemptId())).singleElement()
                .satisfies(call -> assertThat(call.totalTokens()).isEqualTo(15));
    }

    static ReviewWorkerTransactionCoordinatorTest.Fixture semanticFixture() {
        return new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
    }

    static ReviewStepStartTransactionCoordinator.StartedExecution requested(
            ReviewWorkerTransactionCoordinatorTest.Fixture f) {
        return f.coordinator.markRequested(f.claim("worker-a"), FINGERPRINT);
    }

    static ContractModelCallAudit call(UUID attemptId, int attemptNo, String fingerprint, int ordinal) {
        return new ContractModelCallAudit(UUID.randomUUID(), null, attemptId, attemptNo, null,
                ordinal, CallPhase.REPAIR, fingerprint, "OPENAI_COMPATIBLE", "qwen-test",
                CallOutcome.FAILED, "INVALID_PROVIDER_JSON", 11, null,
                null, null, null, null, ReviewWorkerTransactionCoordinatorTest.NOW);
    }
}
