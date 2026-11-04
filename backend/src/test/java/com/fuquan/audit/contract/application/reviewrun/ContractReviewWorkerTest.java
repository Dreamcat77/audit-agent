package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ContractReviewWorkerTest {
    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void timeoutRetainsRunningLeaseUntilSafeReclaimAndFencesLateCompletion(ContractReviewStepType type) throws Exception {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(type);
        configureDiscovery(f);
        try (var executor = new StubbornExecutor(type, (input, context) ->
                new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null));
             var worker = timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            assertThat(executor.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.finished.getCount()).isEqualTo(1);
            assertRunningLease(f);
            var retained = f.attempt.get();
            assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isEmpty();
            assertThat(f.attempt.get()).isEqualTo(retained);
            f.now.set(retained.leaseUntil());
            var reclaimed = f.coordinator.recover(f.currentCandidate(), "worker-b").orElseThrow();
            assertThat(reclaimed.attempt().operationIdentity()).isEqualTo(retained.operationIdentity());
            assertThat(reclaimed.attempt().leaseOwner()).isEqualTo("worker-b");
            assertThat(reclaimed.attempt().versionNo()).isGreaterThan(retained.versionNo());
            var late = new ReviewStepStartTransactionCoordinator.StartedExecution(reclaimed.run(), reclaimed.step(),
                    retained, reclaimed.idempotency(), reclaimed.actor(), reclaimed.command(), reclaimed.f02Configurations());
            executor.release.countDown();
            assertThat(executor.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> f.coordinator.complete(late, f.emptyResult(late)))
                    .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
            assertThat(f.attempt.get()).isEqualTo(reclaimed.attempt());
            verifyNoInteractions(f.completes);
        }
    }

    @Test void semanticTimeoutDefersBusyRecoveryThenClosesUnknownWithoutReplay() throws Exception {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        configureDiscovery(f);
        try (var executor = new StubbornExecutor(ContractReviewStepType.SEMANTIC_AI, (input, context) ->
                new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null));
             var worker = timeoutWorker(f, executor, "worker-a")) {
            worker.scanOnce(4);
            assertThat(executor.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertRunningLease(f);
            assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
            var retained = f.attempt.get();
            worker.scanOnce(4);
            assertThat(f.attempt.get()).isEqualTo(retained);
            f.now.set(retained.leaseUntil());
            worker.scanOnce(4);
            // The same worker must not recover/claim while native computation
            // is still alive. Another instance may safely fence this lease.
            assertRunningLeaseAfterExpiry(f, retained);
            executor.release.countDown();
            assertThat(executor.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isEmpty();
            assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
            assertThat(f.step.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
            assertThat(f.run.get().status()).isEqualTo(ContractReviewRunStatus.UNKNOWN);
            assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
            worker.scanOnce(4);
            assertThat(executor.calls.get()).isEqualTo(1);
            verifyNoInteractions(f.completes);
        }
    }

    static void assertRunningLeaseAfterExpiry(ReviewWorkerTransactionCoordinatorTest.Fixture f,
            ContractReviewStepAttempt retained) {
        assertThat(f.attempt.get()).isEqualTo(retained);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    static void assertRunningLease(ReviewWorkerTransactionCoordinatorTest.Fixture f) {
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(f.step.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(f.run.get().status()).isEqualTo(ContractReviewRunStatus.RUNNING);
        assertThat(f.attempt.get().leaseUntil()).isEqualTo(f.now.get().plusSeconds(60));
        assertThat(f.attempt.get().versionNo()).isGreaterThan(2);
        assertThat(f.attempt.get().completedAt()).isNull();
        assertThat(f.attempt.get().safeReasonCode()).isNull();
    }

    static void configureDiscovery(ReviewWorkerTransactionCoordinatorTest.Fixture f) {
        // Advance the fixture clock for each real heartbeat; a fixed clock cannot extend a lease.
        f.coordinator = spy(f.coordinator);
        doAnswer(call -> {
            f.now.updateAndGet(now -> now.plusMillis(1));
            return call.callRealMethod();
        }).when(f.coordinator).heartbeat(any());
        when(f.workers.findClaimCandidates(anyInt(), any())).thenAnswer(call ->
                f.attempt.get().status() == ContractReviewStepStatus.PENDING ? List.of(f.currentCandidate()) : List.of());
        when(f.workers.findExpiredLeases(anyInt(), any())).thenAnswer(call -> {
            var attempt = f.attempt.get();
            return attempt.status() == ContractReviewStepStatus.RUNNING
                    && !attempt.leaseUntil().isAfter(call.getArgument(1, Instant.class))
                    ? List.of(f.currentCandidate()) : List.of();
        });
    }

    static ContractReviewWorker timeoutWorker(ReviewWorkerTransactionCoordinatorTest.Fixture f,
            ReviewStepExecutor executor, String owner) {
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return f.now.get(); }
        };
        return new ContractReviewWorker(f.workers, f.coordinator, f.inputs, List.of(executor), clock,
                owner, Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    /** Deliberately continues computation after cancellation, like an uninterruptible external operation. */
    static final class StubbornExecutor implements ReviewStepExecutor, AutoCloseable {
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private final ContractReviewStepType type;
        private final java.util.function.BiFunction<FrozenReviewInput, ReviewExecutionContext, StepComputationResult> completion;

        StubbornExecutor(ContractReviewStepType type,
                java.util.function.BiFunction<FrozenReviewInput, ReviewExecutionContext, StepComputationResult> completion) {
            this.type = type;
            this.completion = completion;
        }
        public ContractReviewStepType stepType() { return type; }
        public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
            try {
                calls.incrementAndGet();
                if (type == ContractReviewStepType.SEMANTIC_AI) context.markExternalRequested("d".repeat(64));
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException requested) { interrupted.countDown(); }
                }
                return completion.apply(input, context);
            } finally { finished.countDown(); }
        }
        public void close() throws InterruptedException {
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void siblingAggregateVersionAdvanceDoesNotInvalidateFrozenExecutionContext() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        when(f.workers.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
        when(f.workers.findClaimCandidates(anyInt(), any())).thenReturn(List.of(f.candidate));
        var reads = new AtomicInteger();
        when(f.inputs.load(any(), any(), any())).thenAnswer(call -> {
            var r = f.run.get();
            if (reads.incrementAndGet() > 1) {
                r = new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), r.contractVersion(), r.sourceCandidateId(),
                        r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(), r.contractSnapshotSha256(),
                        r.reviewProfileVersion(), r.ruleVersion(), r.modelPolicyVersion(), r.inputSnapshotIdentity(), r.requestedBy(),
                        r.status(), r.requestedAt(), r.startedAt(), r.completedAt(), r.failedAt(), r.unknownAt(), r.safeReasonCode(), r.versionNo() + 1);
                f.run.set(r);
            }
            return new FrozenReviewInput(r, com.fuquan.audit.contract.infra.memory.LocalContractFixtures.contracts().get(0), List.of());
        });
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.SEMANTIC_AI; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                // The existing FrozenSemanticRequestBuilder requires exact context/input equality.
                assertThat(input.run()).isEqualTo(context.run());
                assertThat(input.run().inputSnapshotIdentity()).isEqualTo(f.run.get().inputSnapshotIdentity());
                context.markExternalRequested("d".repeat(64));
                return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(), List.of(), List.of(), null, null, null);
            }
        };
        try (var worker = new ContractReviewWorker(f.workers, f.coordinator, f.inputs, List.of(executor),
                Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC), "worker-a", Duration.ofSeconds(10), Duration.ofSeconds(30))) {
            worker.scanOnce(4);
        }
        verify(f.completes).complete(any(), any());
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING); // mocked publication seam
    }

    @Test void scanExecutesOnlyCommittedClaimAndOutsideTransactionCoordinator() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH);
        when(f.workers.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
        when(f.workers.findClaimCandidates(anyInt(), any())).thenReturn(List.of(f.candidate));
        var calls = new AtomicInteger();
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.BREACH; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
                assertThat(context.actor().userId()).isEqualTo("initiator-1");
                calls.incrementAndGet();
                throw new ReviewStepProvenFailureException("DETERMINISTIC_TEST_FAILURE", new IllegalStateException());
            }
        };
        try (var worker = new ContractReviewWorker(f.workers, f.coordinator, f.inputs,
                List.of(executor), Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC),
                "worker-a", Duration.ofSeconds(10), Duration.ofSeconds(30))) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.FAILED);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo("DETERMINISTIC_TEST_FAILURE");
        assertThat(f.step.get().safeReasonCode()).isEqualTo("DETERMINISTIC_TEST_FAILURE");
    }

    @Test void executorOutcomeUnknownKeepsItsSafeReasonWithoutAutomaticReexecution() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        when(f.workers.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
        when(f.workers.findClaimCandidates(anyInt(), any())).thenReturn(List.of(f.candidate));
        var calls = new AtomicInteger();
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.SEMANTIC_AI; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                context.markExternalRequested("d".repeat(64));
                calls.incrementAndGet();
                throw new ReviewStepOutcomeUnknownException(
                        "SEMANTIC_OUTCOME_UNKNOWN", new IllegalStateException("safe test cause"));
            }
        };
        try (var worker = new ContractReviewWorker(f.workers, f.coordinator, f.inputs,
                List.of(executor), Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC),
                "worker-a", Duration.ofSeconds(10), Duration.ofSeconds(30))) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo("SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.step.get().safeReasonCode()).isEqualTo("SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.run.get().safeReasonCode()).isEqualTo("SEMANTIC_OUTCOME_UNKNOWN");
    }

    @Test void escapedRequestRuntimeFailureClosesUnknownWithoutAutomaticReexecution() {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.SEMANTIC_AI);
        when(f.workers.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
        when(f.workers.findClaimCandidates(anyInt(), any())).thenReturn(List.of(f.candidate));
        var calls = new AtomicInteger();
        ReviewStepExecutor executor = new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return ContractReviewStepType.SEMANTIC_AI; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                context.markExternalRequested("d".repeat(64));
                calls.incrementAndGet();
                throw new IllegalStateException("unproven outcome");
            }
        };
        try (var worker = new ContractReviewWorker(f.workers, f.coordinator, f.inputs,
                List.of(executor), Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC),
                "worker-a", Duration.ofSeconds(10), Duration.ofSeconds(30))) {
            worker.scanOnce(4);
            worker.scanOnce(4);
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
    }
}
