package com.fuquan.audit.integration.idempotency.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyAdapterTest {

    @Test
    void firstKeyClaimSucceeds() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();

        IdempotencyClaim claim = adapter.claim(request("key-1", "fingerprint-1"));

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.record().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(adapter.size()).isEqualTo(1);
    }

    @Test
    void repeatedKeyReturnsExistingRecordWithoutCreatingAnother() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();
        IdempotencyClaim first = adapter.claim(request("key-1", "fingerprint-1"));

        IdempotencyClaim second = adapter.claim(request("key-1", "fingerprint-1"));

        assertThat(second.acquired()).isFalse();
        assertThat(second.record().id()).isEqualTo(first.record().id());
        assertThat(adapter.size()).isEqualTo(1);
    }

    @Test
    void successfulRecordRetainsOnlyResultResourceIdentity() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();
        IdempotencyClaim claim = adapter.claim(request("key-1", "fingerprint-1"));

        var completed = adapter.markSucceeded(
                claim.record(), "AUDIT_TASK", "task-1", now().plusSeconds(1));

        assertThat(completed.status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(completed.resultResourceId()).isEqualTo("task-1");
        assertThat(completed.versionNo()).isEqualTo(1);
    }

    @Test
    void failedRecordIsReturnedAndNotSilentlyReclaimed() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();
        IdempotencyClaim claim = adapter.claim(request("key-1", "fingerprint-1"));
        var failed = adapter.markFailed(claim.record(), now().plusSeconds(1));

        IdempotencyClaim repeated = adapter.claim(request("key-1", "fingerprint-1"));

        assertThat(failed.resultResourceType()).isEqualTo("AUDIT_TASK");
        assertThat(failed.resultResourceId()).isEqualTo("task-1");
        assertThat(repeated.acquired()).isFalse();
        assertThat(repeated.record().status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(repeated.record().resultResourceId()).isEqualTo("task-1");
    }

    @Test
    void differentKeysAllowDifferentAttempts() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();

        assertThat(adapter.claim(request("key-1", "fp")).acquired()).isTrue();
        assertThat(adapter.claim(request("key-2", "fp")).acquired()).isTrue();
        assertThat(adapter.size()).isEqualTo(2);
    }

    @Test
    void transactionRollbackSeamRemovesOnlyTheOriginalInProgressClaim() {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();
        IdempotencyClaim first = adapter.claim(request("rolled-back", "fp"));

        adapter.rollbackUncommittedClaim(first.record());

        assertThat(adapter.findByScopeAndKey("CONTRACT_F02_REVIEW", "rolled-back")).isEmpty();
        IdempotencyClaim terminal = adapter.claim(request("terminal", "fp"));
        adapter.markSucceeded(terminal.record(), "AUDIT_TASK", "task-1", now().plusSeconds(1));
        adapter.rollbackUncommittedClaim(terminal.record());
        assertThat(adapter.findByScopeAndKey("CONTRACT_F02_REVIEW", "terminal")).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
    }

    @Test
    void twoConcurrentClaimsHaveExactlyOneAcquirer() throws Exception {
        InMemoryIdempotencyAdapter adapter = new InMemoryIdempotencyAdapter();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (adapter.claim(request("same-key", "same-fingerprint")).acquired()) {
                        acquired.incrementAndGet();
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

        assertThat(acquired).hasValue(1);
        assertThat(adapter.size()).isEqualTo(1);
    }

    private IdempotencyRequest request(String key, String fingerprint) {
        return new IdempotencyRequest(
                "CONTRACT_F02_REVIEW", key, "user-1", "CONTRACT", "contract-1", fingerprint,
                "AUDIT_TASK", "task-1", "request-1", "trace-1", now());
    }

    private Instant now() {
        return Instant.parse("2026-08-17T00:00:00Z");
    }
}
