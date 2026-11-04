package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContractReviewWorkerAdmissionTest {
    static final class HandoffPool extends ThreadPoolExecutor {
        final CountDownLatch firstAfterExecute = new CountDownLatch(1), releaseAfterExecute = new CountDownLatch(1);
        final CountDownLatch secondSubmission = new CountDownLatch(1);
        final List<Runnable> submitted = new CopyOnWriteArrayList<>();
        final AtomicInteger afters = new AtomicInteger(), rejected = new AtomicInteger();
        HandoffPool(BlockingQueue<Runnable> queue) {
            super(1, 1, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
                var thread = new Thread(runnable, "worker-admission-test"); thread.setDaemon(true); return thread;
            }, new AbortPolicy());
        }
        @Override public void execute(Runnable task) {
            submitted.add(task);
            try { super.execute(task); }
            catch (RejectedExecutionException error) { rejected.incrementAndGet(); throw error; }
            finally { if (submitted.size() == 2) secondSubmission.countDown(); }
        }
        @Override protected void afterExecute(Runnable task, Throwable error) {
            if (afters.incrementAndGet() == 1) {
                firstAfterExecute.countDown();
                try { releaseAfterExecute.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    static final class Setup {
        final List<ReviewWorkerTransactionCoordinatorTest.Fixture> fixtures = List.of(
                new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH),
                new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH),
                new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH));
        final ContractReviewWorkerRepository repository = mock(ContractReviewWorkerRepository.class);
        final ReviewWorkerTransactionCoordinator transactions = mock(ReviewWorkerTransactionCoordinator.class);
        final FrozenReviewInputLoader inputs = mock(FrozenReviewInputLoader.class);
        final AtomicInteger executions = new AtomicInteger(), completions = new AtomicInteger();
        Setup(int candidates) {
            when(repository.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
            when(repository.findClaimCandidates(anyInt(), any())).thenAnswer(invocation -> fixtures.subList(0, candidates).stream()
                    .filter(f -> f.attempt.get().status() == ContractReviewStepStatus.PENDING)
                    .map(ReviewWorkerTransactionCoordinatorTest.Fixture::currentCandidate).toList());
            when(transactions.claim(any(), anyString())).thenAnswer(invocation -> fixtures.stream()
                    .filter(f -> f.currentCandidate().stepId().equals(invocation.getArgument(0,
                            ContractReviewWorkerRepository.Candidate.class).stepId())).findFirst().orElseThrow()
                    .coordinator.claim(invocation.getArgument(0), invocation.getArgument(1)));
            when(inputs.load(any(), any(), any())).thenAnswer(invocation -> fixtures.stream()
                    .filter(f -> f.run.get().runId().equals(invocation.getArgument(2))).findFirst().orElseThrow()
                    .inputs.load(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            doAnswer(invocation -> { completions.incrementAndGet(); return null; }).when(transactions).complete(any(), any());
            when(transactions.heartbeat(any())).thenAnswer(invocation -> invocation.getArgument(0));
        }
        ReviewStepExecutor fast() {
            return executor((input, context) -> {
                executions.incrementAndGet();
                return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null);
            });
        }
        ReviewStepExecutor executor(java.util.function.BiFunction<FrozenReviewInput, ReviewExecutionContext, StepComputationResult> body) {
            return new ReviewStepExecutor() {
                public ContractReviewStepType stepType() { return ContractReviewStepType.BREACH; }
                public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) { return body.apply(input, context); }
            };
        }
        ContractReviewWorker worker(ReviewStepExecutor executor, Duration timeout,
                java.util.function.Function<BlockingQueue<Runnable>, ThreadPoolExecutor> factory) {
            return new ContractReviewWorker(repository, transactions, inputs, List.of(executor),
                    Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC), "worker-admission",
                    Duration.ofMillis(20), timeout, factory);
        }
    }

    static HandoffPool readyPool(BlockingQueue<Runnable> queue) {
        var pool = new HandoffPool(queue); pool.releaseAfterExecute.countDown(); return pool;
    }

    @Test void completedCallableHandsOffWhilePoolStillInsideAfterExecuteWithoutLosingNextClaim() throws Exception {
        var setup = new Setup(2); var pool = new HandoffPool[1];
        var scanner = Executors.newSingleThreadExecutor();
        try (var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), queue -> pool[0] = new HandoffPool(queue))) {
            var scan = scanner.submit(() -> worker.scanOnce(4));
            try {
                assertThat(pool[0].firstAfterExecute.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pool[0].secondSubmission.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pool[0].rejected).hasValue(0);
                assertThat(pool[0].getQueue()).hasSize(1);
            } finally { pool[0].releaseAfterExecute.countDown(); }
            scan.get(5, TimeUnit.SECONDS);
            assertThat(setup.executions).hasValue(2);
            assertThat(setup.completions).hasValue(2);
        } finally { scanner.shutdownNow(); }
    }

    @Test void concurrentScanReturnsWithoutDiscoveryOrAnotherClaim() throws Exception {
        var setup = new Setup(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(setup.repository.findExpiredLeases(anyInt(), any())).thenAnswer(invocation -> {
            entered.countDown(); release.await(); return List.of();
        });
        var scans = Executors.newFixedThreadPool(2);
        try (var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), ContractReviewWorkerAdmissionTest::readyPool)) {
            var first = scans.submit(() -> worker.scanOnce(1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = scans.submit(() -> worker.scanOnce(1));
            try { assertThatCode(() -> second.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException(); }
            finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            verify(setup.repository, times(1)).findExpiredLeases(anyInt(), any());
        } finally { release.countDown(); scans.shutdownNow(); }
    }

    @Test void cancelledStubbornComputationKeepsAdmissionClosedUntilItsActualExit() throws Exception {
        var setup = new Setup(2);
        try (var executor = new ContractReviewWorkerTest.StubbornExecutor(ContractReviewStepType.BREACH,
                (input, context) -> new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null));
             var worker = setup.worker(executor, Duration.ofMillis(150), ContractReviewWorkerAdmissionTest::readyPool)) {
            worker.scanOnce(2);
            assertThat(executor.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(setup.fixtures.get(1).attempt.get().status()).isEqualTo(ContractReviewStepStatus.PENDING);
            worker.scanOnce(2);
            verify(setup.transactions, times(1)).claim(any(), anyString());
            verify(setup.transactions, never()).complete(any(), any());
            verify(setup.transactions, never()).close(any(), any(), anyString());
        }
    }

    @Test void closedWorkerDoesNotDiscoverOrClaimAndStillValidatesTheLimit() {
        var setup = new Setup(1);
        var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), ContractReviewWorkerAdmissionTest::readyPool);
        worker.close(); worker.scanOnce(1);
        assertThatThrownBy(() -> worker.scanOnce(0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(setup.repository, setup.transactions);
    }

    @Test void closeCancelsQueuedBeforeStartWithoutPublishingFailureOrRunningIt() throws Exception {
        var setup = new Setup(2); var pool = new HandoffPool[1];
        var scanner = Executors.newSingleThreadExecutor();
        try (var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), queue -> pool[0] = new HandoffPool(queue))) {
            var scan = scanner.submit(() -> worker.scanOnce(2));
            try {
                assertThat(pool[0].firstAfterExecute.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pool[0].secondSubmission.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pool[0].getQueue()).hasSize(1);
                worker.close(); scan.get(5, TimeUnit.SECONDS);
                assertThat((Future<?>) pool[0].submitted.get(1)).isCancelled();
                assertThat(setup.executions).hasValue(1);
                assertThat(setup.completions).hasValue(1);
                assertThat(setup.fixtures.get(1).attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
                verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
                verify(setup.transactions, never()).close(any(), any(), anyString());
                worker.scanOnce(2);
                verify(setup.transactions, times(2)).claim(any(), anyString());
            } finally { pool[0].releaseAfterExecute.countDown(); }
        } finally { scanner.shutdownNow(); }
    }

    @Test void timeoutRemovesQueuedTaskAndNeverPublishesAFalseFailureOrLateResult() throws Exception {
        var setup = new Setup(2); var pool = new HandoffPool[1];
        var scanner = Executors.newSingleThreadExecutor();
        try (var worker = setup.worker(setup.fast(), Duration.ofMillis(200), queue -> pool[0] = new HandoffPool(queue))) {
            var scan = scanner.submit(() -> worker.scanOnce(2));
            try {
                assertThat(pool[0].secondSubmission.await(5, TimeUnit.SECONDS)).isTrue();
                scan.get(5, TimeUnit.SECONDS);
                assertThat(pool[0].getQueue()).isEmpty();
                assertThat((Future<?>) pool[0].submitted.get(1)).isCancelled();
                assertThat(setup.executions).hasValue(1);
                assertThat(setup.completions).hasValue(1);
                verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
                assertThat(setup.fixtures.get(1).attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            } finally { pool[0].releaseAfterExecute.countDown(); }
        } finally { scanner.shutdownNow(); }
    }

    @Test void closedWorkerDoesNotPublishAnInterruptIgnoringLateFailure() throws Exception {
        var setup = new Setup(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var scanner = Executors.newSingleThreadExecutor();
        var failing = setup.executor((input, context) -> {
            entered.countDown(); awaitIgnoringInterrupt(release);
            throw new ReviewStepProvenFailureException("DETERMINISTIC_TEST_FAILURE", new IllegalStateException());
        });
        try (var worker = setup.worker(failing, Duration.ofSeconds(5), ContractReviewWorkerAdmissionTest::readyPool)) {
            var scan = scanner.submit(() -> worker.scanOnce(1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.close(); release.countDown(); scan.get(5, TimeUnit.SECONDS);
            verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
            verify(setup.transactions, never()).complete(any(), any());
        } finally { release.countDown(); scanner.shutdownNow(); }
    }

    @Test void oldCompletedFutureRunFinallyCannotReleaseANewerStubbornComputationToken() throws Exception {
        var setup = new Setup(3); var pool = new HandoffPool[1];
        var calls = new AtomicInteger(); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var executor = setup.executor((input, context) -> {
            if (calls.incrementAndGet() == 2) {
                try {
                    while (true) {
                        try { release.await(); break; }
                        catch (InterruptedException requested) { interrupted.countDown(); }
                    }
                } finally { finished.countDown(); }
            }
            return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                    List.of(), List.of(), null, null, null);
        });
        try (var worker = setup.worker(executor, Duration.ofMillis(200), queue -> pool[0] = readyPool(queue))) {
            worker.scanOnce(3);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pool[0].submitted).hasSize(2);
            // FutureTask.super.run is idempotent; our old run-finally still executes.
            pool[0].submitted.get(0).run();
            worker.scanOnce(3);
            assertThat(setup.fixtures.get(2).attempt.get().status()).isEqualTo(ContractReviewStepStatus.PENDING);
            verify(setup.transactions, times(2)).claim(any(), anyString());
            assertThat(setup.completions).hasValue(1);
        } finally { release.countDown(); assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void closeStopsRenewalAfterAnAlreadyEnteredHeartbeatReturns() throws Exception {
        var setup = new Setup(1); var heartbeatEntered = new CountDownLatch(1); var returnHeartbeat = new CountDownLatch(1);
        var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        when(setup.transactions.heartbeat(any())).thenAnswer(invocation -> {
            heartbeatEntered.countDown(); returnHeartbeat.await(); return invocation.getArgument(0);
        });
        var scanner = Executors.newSingleThreadExecutor();
        var executor = setup.executor((input, context) -> {
            try { awaitIgnoringInterrupt(release); }
            finally { finished.countDown(); }
            return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                    List.of(), List.of(), null, null, null);
        });
        try (var worker = setup.worker(executor, Duration.ofMillis(250), ContractReviewWorkerAdmissionTest::readyPool)) {
            var scan = scanner.submit(() -> worker.scanOnce(1));
            assertThat(heartbeatEntered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.close(); returnHeartbeat.countDown(); scan.get(5, TimeUnit.SECONDS);
            verify(setup.transactions, times(1)).heartbeat(any());
            verify(setup.transactions, never()).complete(any(), any());
            verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
        } finally {
            returnHeartbeat.countDown(); release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue(); scanner.shutdownNow();
        }
    }

    @Test void rejectedDispatchRetainsClaimForRecoveryWithoutInventingBusinessFailure() {
        var setup = new Setup(1);
        try (var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), queue ->
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override public void execute(Runnable task) { throw new RejectedExecutionException("synthetic rejection"); }
                })) {
            worker.scanOnce(1);
            assertThat(setup.fixtures.get(0).attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            assertThat(setup.executions).hasValue(0);
            assertThat(setup.completions).hasValue(0);
            verify(setup.transactions, never()).close(any(), any(), anyString());
            verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
        }
    }

    @Test void shutdownAfterDequeueButBeforeCallableStartNeverInvokesTheExecutor() throws Exception {
        var setup = new Setup(1); var beforeRun = new CountDownLatch(1); var allowRun = new CountDownLatch(1);
        var afterRun = new CountDownLatch(1); var heartbeat = new CountDownLatch(1); var returnHeartbeat = new CountDownLatch(1);
        when(setup.transactions.heartbeat(any())).thenAnswer(invocation -> {
            heartbeat.countDown(); returnHeartbeat.await(); return invocation.getArgument(0);
        });
        var scanner = Executors.newSingleThreadExecutor();
        try (var worker = setup.worker(setup.fast(), Duration.ofSeconds(5), queue ->
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override protected void beforeExecute(Thread thread, Runnable task) {
                        beforeRun.countDown(); awaitIgnoringInterrupt(allowRun);
                    }
                    @Override protected void afterExecute(Runnable task, Throwable failure) { afterRun.countDown(); }
                })) {
            var scan = scanner.submit(() -> worker.scanOnce(1));
            try {
                assertThat(beforeRun.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(heartbeat.await(5, TimeUnit.SECONDS)).isTrue();
                worker.close(); allowRun.countDown();
                assertThat(afterRun.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(setup.executions).hasValue(0);
                verify(setup.inputs, never()).load(any(), any(), any());
            } finally { allowRun.countDown(); returnHeartbeat.countDown(); }
            scan.get(5, TimeUnit.SECONDS);
            verify(setup.transactions, never()).complete(any(), any());
            verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
        } finally { allowRun.countDown(); returnHeartbeat.countDown(); scanner.shutdownNow(); }
    }

    @Test void stubbornComputationCannotEnterExternalRequestCallbackAfterClose() throws Exception {
        var setup = new Setup(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1); var externalCalls = new AtomicInteger();
        when(setup.transactions.markRequested(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        var executor = setup.executor((input, context) -> {
            entered.countDown(); awaitIgnoringInterrupt(release);
            try {
                context.markExternalRequested("d".repeat(64));
                externalCalls.incrementAndGet();
                return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null);
            } finally { finished.countDown(); }
        });
        var scanner = Executors.newSingleThreadExecutor();
        try (var worker = setup.worker(executor, Duration.ofSeconds(5), ContractReviewWorkerAdmissionTest::readyPool)) {
            var scan = scanner.submit(() -> worker.scanOnce(1));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                worker.close(); release.countDown();
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                scan.get(5, TimeUnit.SECONDS);
                verify(setup.transactions, never()).markRequested(any(), anyString());
                assertThat(externalCalls).hasValue(0);
                verify(setup.transactions, never()).complete(any(), any());
                verify(setup.transactions, never()).close(any(), any(), anyString(), anyList());
            } finally { release.countDown(); }
        } finally { release.countDown(); scanner.shutdownNow(); }
    }

    static void awaitIgnoringInterrupt(CountDownLatch release) {
        while (true) {
            try { release.await(); return; }
            catch (InterruptedException requested) { /* Deliberately stubborn native surrogate. */ }
        }
    }
}
