package com.fuquan.audit.security.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.security.config.LocalAccountProperties;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryUserAccountRepositoryTest {

    @Test
    void findsLiveDisplayNameFromTheSameSeededAccountStore() {
        InMemoryUserAccountRepository repository = repository();
        UserAccount account = account();
        repository.seed(account);

        UserIdentityQueryPort identityQuery = repository;

        assertThat(identityQuery.findDisplayNameByUserId(account.id())).contains("Concurrency Test");
        assertThat(identityQuery.findDisplayNameByUserId("deleted-user")).isEmpty();
    }

    @Test
    void concurrentPasswordFailuresDoNotLoseIncrements() throws Exception {
        InMemoryUserAccountRepository repository = repository();
        UserAccount account = account();
        repository.seed(account);
        int concurrentAttempts = 24;
        CountDownLatch ready = new CountDownLatch(concurrentAttempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrentAttempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    Instant now = Instant.now();
                    repository.recordFailedLogin(account.id(), 5, now.plusSeconds(300), now);
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

        UserAccount updated = repository.findByUsername(account.username()).orElseThrow();
        assertThat(updated.failedLoginCount()).isEqualTo(concurrentAttempts);
        assertThat(updated.versionNo()).isEqualTo(concurrentAttempts);
        assertThat(updated.status()).isEqualTo(UserAccountStatus.LOCKED);
        assertThat(updated.lockedUntil()).isNotNull();
    }

    private InMemoryUserAccountRepository repository() {
        ObservabilityProperties observability = new ObservabilityProperties();
        observability.setFlowDebugEnabled(false);
        return new InMemoryUserAccountRepository(
                new LocalAccountProperties(),
                new FlowDebugLogger(observability));
    }

    private UserAccount account() {
        Instant now = Instant.now();
        return new UserAccount(
                UUID.randomUUID().toString(),
                "concurrent-" + UUID.randomUUID(),
                "{noop}synthetic-hash",
                "Concurrency Test",
                UserAccountStatus.ACTIVE,
                0,
                null,
                null,
                now,
                now,
                now,
                0,
                Set.of("AUDITOR"));
    }
}
