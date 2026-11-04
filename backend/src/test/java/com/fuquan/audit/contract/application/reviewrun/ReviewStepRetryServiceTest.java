package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReviewStepRetryServiceTest {
    @Test void failedRetryAppendsAttemptTwoAndPreservesTerminalAttemptAndBusinessInitiator() {
        var f = new RetryFixture(false);
        var original = f.previous;
        var result = f.service.retry(f.command("retry-one", "请重新审核"));
        assertThat(result.attempt().attemptNo()).isEqualTo(2);
        assertThat(result.attempt().attemptId()).isNotEqualTo(original.attemptId());
        assertThat(result.attempt().operationIdentity()).isNotEqualTo(original.operationIdentity());
        assertThat(result.attempt().status()).isEqualTo(ContractReviewStepStatus.PENDING);
        assertThat(f.history.get(original.attemptId())).isEqualTo(original);
        assertThat(f.base.step.get().currentAttemptNo()).isEqualTo(2);
        assertThat(f.base.run.get().requestedBy()).isEqualTo("initiator-1");
        assertThat(f.base.events.get(f.base.events.size() - 1).userId()).isEqualTo("retry-actor");
    }

    @Test void sameKeyReopensExactNewAttemptAfterPointerAdvancedAndChangedReasonConflicts() {
        var f = new RetryFixture(false);
        var command = f.command("retry-key", " reason ");
        var first = f.service.retry(command);
        var replayed = f.service.retry(command);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.attempt().attemptId()).isEqualTo(first.attempt().attemptId());
        assertThat(f.history).hasSize(2);
        assertThatThrownBy(() -> f.service.retry(f.command("retry-key", "changed")))
                .isInstanceOf(BusinessException.class);
        assertThat(f.history).hasSize(2);
    }

    @Test void twoRetriesRaceOnlyOneAttemptTwoWins() throws Exception {
        var f = new RetryFixture(false);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { gate.await(); return f.tryRetry("a"); });
            var b = pool.submit(() -> { gate.await(); return f.tryRetry("b"); });
            gate.countDown();
            assertThat(Arrays.asList(a.get(), b.get()).stream().filter(Objects::nonNull).count()).isEqualTo(1);
            assertThat(f.history).hasSize(2);
            assertThat(f.history.values().stream().filter(value -> value.attemptNo() == 2).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void unknownNeverAutomaticallySupersedesAndUnauthorizedActorCannotRetry() {
        var unknown = new RetryFixture(true);
        assertThatThrownBy(() -> unknown.service.retry(unknown.command("unknown", "reason")))
                .isInstanceOf(BusinessException.class);
        assertThat(unknown.history).hasSize(1);
        var failed = new RetryFixture(false);
        var service = failed.service(new CurrentUser("unprivileged", "user", "User", Set.of()));
        assertThatThrownBy(() -> service.retry(failed.command("no-role", "reason")))
                .isInstanceOf(BusinessException.class);
        assertThat(failed.history).hasSize(1);
    }

    static final class RetryFixture {
        final ReviewWorkerTransactionCoordinatorTest.Fixture base =
                new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH);
        final Map<UUID, ContractReviewStepAttempt> history = new ConcurrentHashMap<>();
        final Map<Integer, UUID> numbers = new ConcurrentHashMap<>();
        final ContractReviewStepAttempt previous;
        final ContractReviewStep priorStep;
        final ContractReviewRun priorRun;
        final ReviewStepRetryService service;

        RetryFixture(boolean unknown) {
            var execution = base.claim("worker-a");
            base.coordinator.close(execution, unknown ? ContractReviewStepStatus.UNKNOWN : ContractReviewStepStatus.FAILED,
                    "TEST_TERMINAL_REASON");
            previous = base.attempt.get(); priorStep = base.step.get(); priorRun = base.run.get();
            history.put(previous.attemptId(), previous); numbers.put(1, previous.attemptId());
            when(base.attempts.findAttemptById(any())).thenAnswer(call -> Optional.ofNullable(history.get(call.getArgument(0))));
            doAnswer(call -> {
                var value = call.<ContractReviewStepAttempt>getArgument(0);
                if (numbers.putIfAbsent(value.attemptNo(), value.attemptId()) != null)
                    throw ReviewWorkerLeasePolicy.conflict();
                history.put(value.attemptId(), value); return null;
            }).when(base.attempts).insert(any());
            doAnswer(call -> {
                if (!base.step.compareAndSet(call.getArgument(0), call.getArgument(1))) throw ReviewWorkerLeasePolicy.conflict();
                if (!base.run.compareAndSet(call.getArgument(2), call.getArgument(3))) throw ReviewWorkerLeasePolicy.conflict();
                return null;
            }).when(base.workers).advanceRetry(any(), any(), any(), any());
            service = service(new CurrentUser("retry-actor", "auditor", "Audit User", Set.of("AUDITOR")));
        }
        ReviewStepRetryService service(CurrentUser actor) {
            return new ReviewStepRetryService(base.workers, base.runs, base.steps, base.attempts, base.idempotency,
                    base.events::add, () -> Optional.of(actor), Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW,
                    ZoneOffset.UTC), new ReviewStepRetryPolicy());
        }
        RetryReviewStepCommand command(String key, String reason) {
            return new RetryReviewStepCommand(priorRun.projectId(), priorRun.contractId(), priorRun.runId(), priorStep.stepId(),
                    priorRun.versionNo(), priorStep.versionNo(), previous.attemptId(), previous.attemptNo(), previous.versionNo(),
                    previous.status(), previous.externalCallState(), reason, key,
                    new ProjectRequestMetadata("request", "trace", null));
        }
        ReviewStepRetryService.RetryResult tryRetry(String key) {
            try { return service.retry(command(key, "reason")); } catch (BusinessException conflict) { return null; }
        }
    }
}
