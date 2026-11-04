package com.fuquan.audit.contract.application.project;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCandidatePromotionConcurrencyTest {

    @Test
    void competingKeysPublishOneContractAssociationCandidateWinnerAndAudit() throws Exception {
        PromotionTestSupport support = new PromotionTestSupport();
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = () -> run(start, () -> support.service.promote(support.command("key-a")));
            Callable<Object> second = () -> run(start, () -> support.service.promote(support.command("key-b")));
            var results = List.of(workers.submit(first), workers.submit(second));
            start.countDown();

            long successes = 0;
            long conflicts = 0;
            for (var future : results) {
                Object outcome = future.get();
                if (outcome instanceof ContractCandidatePromotionResult) successes++;
                if (outcome instanceof com.fuquan.audit.common.exception.BusinessException) conflicts++;
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(support.formal.get()).isNotNull();
            assertThat(support.associations.findByProjectId(support.project.id())).hasSize(1);
            assertThat(support.audits).hasSize(1);
            assertThat(support.candidate.get().promotedContractId()).isEqualTo(support.formal.get().id());
        } finally {
            workers.shutdownNow();
        }
    }

    private static Object run(CountDownLatch start, java.util.concurrent.Callable<?> operation) throws Exception {
        start.await();
        try { return operation.call(); }
        catch (RuntimeException failure) { return failure; }
    }
}
