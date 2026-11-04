package com.fuquan.audit.review.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryAuditTaskRepositoryConcurrencyTest {

    @Test
    void staleVersionTransitionFails() {
        InMemoryAuditTaskRepository repository = new InMemoryAuditTaskRepository();
        AuditTask task = task();
        repository.create(task);
        repository.transition(task.id(), AuditTaskStatus.PENDING, 0, AuditTaskStatus.PROCESSING, now());

        assertThatThrownBy(() -> repository.transition(
                task.id(), AuditTaskStatus.PENDING, 0, AuditTaskStatus.PROCESSING, now()))
                .isInstanceOf(AuditTaskConcurrencyException.class);
    }

    @Test
    void twoThreadsCompetingForSameVersionHaveExactlyOneWinner() throws Exception {
        InMemoryAuditTaskRepository repository = new InMemoryAuditTaskRepository();
        AuditTask task = task();
        repository.create(task);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        repository.transition(
                                task.id(), AuditTaskStatus.PENDING, 0,
                                AuditTaskStatus.PROCESSING, now());
                        success.incrementAndGet();
                    } catch (AuditTaskConcurrencyException conflict) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(success).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(repository.findById(task.id()).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void illegalTransitionIsConflictAtRepositoryBoundary() {
        InMemoryAuditTaskRepository repository = new InMemoryAuditTaskRepository();
        AuditTask task = task();
        repository.create(task);

        assertThatThrownBy(() -> repository.transition(
                task.id(), AuditTaskStatus.PENDING, 0, AuditTaskStatus.DONE, now()))
                .isInstanceOf(AuditTaskConcurrencyException.class);
    }

    private AuditTask task() {
        return AuditTask.pending(
                UUID.randomUUID(), "CONTRACT", UUID.randomUUID(), 1, "F02_DETERMINISTIC",
                "user-1", "request-1", "0123456789abcdef0123456789abcdef", now());
    }

    private Instant now() {
        return Instant.parse("2026-08-17T00:00:00Z");
    }
}
