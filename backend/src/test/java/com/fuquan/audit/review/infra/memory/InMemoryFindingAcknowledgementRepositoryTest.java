package com.fuquan.audit.review.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryFindingAcknowledgementRepositoryTest {

    @Test
    void createsOneAcknowledgementPerCheckResultAndReadsItByResult() {
        InMemoryAuditCheckResultRepository results = new InMemoryAuditCheckResultRepository();
        AuditCheckResult result = result(UUID.randomUUID());
        results.appendAll(List.of(result));
        InMemoryFindingAcknowledgementRepository repository = new InMemoryFindingAcknowledgementRepository(results);
        FindingAcknowledgement acknowledgement = acknowledgement(result.id(), Instant.parse("2026-08-25T08:15:30Z"));

        repository.create(acknowledgement);

        assertThat(repository.findByCheckResultId(result.id())).contains(acknowledgement);
        assertThat(results.findById(result.id())).contains(result);
        assertThatIllegalStateException().isThrownBy(() -> repository.create(
                acknowledgement(result.id(), Instant.parse("2026-08-25T08:16:00Z"))));
    }

    @Test
    void concurrentSecondCreateConflictsWithoutReplacingFirstFact() throws Exception {
        InMemoryAuditCheckResultRepository results = new InMemoryAuditCheckResultRepository();
        AuditCheckResult result = result(UUID.randomUUID());
        results.appendAll(List.of(result));
        InMemoryFindingAcknowledgementRepository repository = new InMemoryFindingAcknowledgementRepository(results);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                final int attempt = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        repository.create(acknowledgement(
                                result.id(), Instant.parse("2026-08-25T08:15:30Z").plusSeconds(attempt)));
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            assertThat(futures.stream().filter(future -> get(future)).count()).isEqualTo(1);
            assertThat(repository.findByCheckResultId(result.id())).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void findsTaskAcknowledgementsByAcknowledgementTimeThenId() {
        InMemoryAuditCheckResultRepository results = new InMemoryAuditCheckResultRepository();
        UUID taskId = UUID.randomUUID();
        AuditCheckResult first = result(taskId);
        AuditCheckResult second = result(taskId);
        AuditCheckResult third = result(taskId);
        results.appendAll(List.of(first, second, third));
        InMemoryFindingAcknowledgementRepository repository = new InMemoryFindingAcknowledgementRepository(results);
        Instant sameTime = Instant.parse("2026-08-25T08:15:30Z");
        FindingAcknowledgement later = acknowledgement(first.id(), sameTime.plusSeconds(1));
        FindingAcknowledgement equalTimeHigherId = acknowledgement(second.id(), sameTime, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        FindingAcknowledgement equalTimeLowerId = acknowledgement(third.id(), sameTime, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        repository.create(later);
        repository.create(equalTimeHigherId);
        repository.create(equalTimeLowerId);

        assertThat(repository.findByTaskId(taskId))
                .containsExactly(equalTimeLowerId, equalTimeHigherId, later);
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private AuditCheckResult result(UUID taskId) {
        return new AuditCheckResult(
                UUID.randomUUID(), taskId, "RULE", null, CheckStatus.FAIL, RiskLevel.RED,
                true, null, null, "detail", 2, Instant.parse("2026-08-25T08:00:00Z"));
    }

    private FindingAcknowledgement acknowledgement(UUID resultId, Instant at) {
        return acknowledgement(resultId, at, UUID.randomUUID());
    }

    private FindingAcknowledgement acknowledgement(UUID resultId, Instant at, UUID id) {
        return new FindingAcknowledgement(id, resultId, "user", at, "request", "trace", 2);
    }
}
