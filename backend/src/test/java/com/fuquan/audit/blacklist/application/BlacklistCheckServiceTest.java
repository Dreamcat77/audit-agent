package com.fuquan.audit.blacklist.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.blacklist.infra.memory.InMemoryBlacklistAlertRepository;
import com.fuquan.audit.blacklist.infra.memory.InMemoryBlacklistRuleRepository;
import com.fuquan.audit.blacklist.infra.memory.InMemoryBlacklistSupplierRepository;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlacklistCheckServiceTest {

    private InMemoryBlacklistSupplierRepository suppliers;
    private InMemoryBlacklistAlertRepository alerts;
    private BlacklistCheckService service;

    @BeforeEach
    void setUp() {
        suppliers = new InMemoryBlacklistSupplierRepository();
        alerts = new InMemoryBlacklistAlertRepository();
        service = new BlacklistCheckService(
                suppliers, alerts, new InMemoryBlacklistRuleRepository(),
                mock(FlowDebugLogger.class));
    }

    @Test
    void creditCodeExactMatchHasHighestSignal() {
        suppliers.save(BlacklistSupplier.create(
                "历史名称", "913100000000000001", "行业失信名单",
                "列入行业失信名单", "外部接口", LocalDate.now(), null, "tester"));

        BlacklistCheckPort.CheckOutcome outcome = service.check(new BlacklistCheckPort.CheckCommand(
                "B-001", 3, null, "当前名称", "913100000000000001"));

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).matchType()).isEqualTo("EXACT");
        assertThat(outcome.matches().get(0).alertLevel()).isEqualTo("CRITICAL");
        assertThat(outcome.persistedAlert()).isNotNull();
    }

    @Test
    void fuzzyMatchAndBatchDeduplicationAreSupported() {
        suppliers.save(BlacklistSupplier.create(
                "某供应商A", null, "本单位黑名单", "一般违规",
                "内部录入", LocalDate.now(), null, "tester"));

        BlacklistCheckPort.CheckCommand command =
                new BlacklistCheckPort.CheckCommand("B-002", 2, null, "某供应商B", null);
        BlacklistCheckPort.CheckOutcome first = service.check(command);
        BlacklistCheckPort.CheckOutcome second = service.check(command);

        assertThat(first.matches()).hasSize(1);
        assertThat(first.matches().get(0).matchType()).isEqualTo("FUZZY");
        assertThat(first.matches().get(0).alertLevel()).isEqualTo("WARNING");
        assertThat(first.persistedAlert()).isNotNull();
        assertThat(second.persistedAlert()).isNull();
        assertThat(alerts.findAll(null)).hasSize(1);
    }

    @Test
    void localBlacklistMatchWithoutSupplierCodeUsesConfiguredCriticalRule() {
        suppliers.save(BlacklistSupplier.create(
                "电子科技有限公司", null, "本单位黑名单", "测试命中记录",
                "内部录入", LocalDate.now(), null, "tester"));
        service = new BlacklistCheckService(
                suppliers, alerts, criticalLocalBlacklistRuleRepository(),
                mock(FlowDebugLogger.class));

        BlacklistCheckPort.CheckOutcome outcome = service.check(new BlacklistCheckPort.CheckCommand(
                "B-CRITICAL", 1, null, "电子科技有限公司", null));

        assertThat(outcome.matches()).singleElement()
                .extracting(BlacklistMatchResult::alertLevel)
                .isEqualTo("CRITICAL");
        assertThat(outcome.persistedAlert()).extracting(BlacklistAlert::alertLevel)
                .isEqualTo("CRITICAL");
    }

    private static com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository
            criticalLocalBlacklistRuleRepository() {
        var repository = new InMemoryBlacklistRuleRepository();
        repository.save(new com.fuquan.audit.blacklist.domain.BlacklistRule(
                UUID.randomUUID(), "本单位黑名单严重预警", "CATEGORY", "本单位黑名单",
                "CRITICAL", false, true, java.time.Instant.now(), java.time.Instant.now()));
        return repository;
    }

    @Test
    void expiryDateDoesNotDisableAnActiveBlacklist() {
        suppliers.save(BlacklistSupplier.create(
                "已失效供应商", "CODE-OLD", "本单位黑名单", "历史违规",
                "内部录入", LocalDate.now().minusDays(10), LocalDate.now().minusDays(1), "tester"));

        BlacklistCheckPort.CheckOutcome outcome = service.check(new BlacklistCheckPort.CheckCommand(
                "B-003", 1, null, "已失效供应商", "CODE-OLD"));

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.persistedAlert()).isNotNull();
    }

    @Test
    void levenshteinDistanceKeepsExactSemanticsForLongNamesWithBoundedMemory() {
        String original = "某".repeat(10_000) + "甲";
        String changed = "某".repeat(10_000) + "乙";

        assertThat(BlacklistCheckService.levenshteinDistance(original, changed)).isEqualTo(1);
        assertThat(BlacklistCheckService.levenshteinDistance("供应商", "供应商有限公司")).isEqualTo(4);
    }

    @Test
    void batchCheckLoadsSuppliersAndRulesOnlyOnce() {
        BlacklistSupplierRepository supplierRepository = mock(BlacklistSupplierRepository.class);
        BlacklistRuleRepository ruleRepository = mock(BlacklistRuleRepository.class);
        BlacklistAlertRepository alertRepository = mock(BlacklistAlertRepository.class);
        when(supplierRepository.findAllActive()).thenReturn(List.of());
        when(ruleRepository.findAllActive()).thenReturn(List.of());
        BlacklistCheckService batchService = new BlacklistCheckService(
                supplierRepository, alertRepository, ruleRepository, mock(FlowDebugLogger.class));

        List<BlacklistCheckPort.CheckOutcome> outcomes = batchService.checkBatch(List.of(
                new BlacklistCheckPort.CheckCommand("B-BATCH", 1, null, "供应商甲", null),
                new BlacklistCheckPort.CheckCommand("B-BATCH", 2, null, "供应商乙", null),
                new BlacklistCheckPort.CheckCommand("B-BATCH", 3, null, "供应商丙", null)));

        assertThat(outcomes).hasSize(3).allMatch(outcome -> !outcome.matched());
        verify(supplierRepository, times(1)).findAllActive();
        verify(ruleRepository, times(1)).findAllActive();
    }

    @Test
    void concurrentChecksCreateOnlyOneAlertForTheSameOperationKey() throws Exception {
        suppliers.save(BlacklistSupplier.create(
                "并发供应商", "CONCURRENT-CODE", "本单位黑名单", "严重违规",
                "内部录入", LocalDate.now(), null, "tester"));
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BlacklistCheckPort.CheckOutcome>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.check(new BlacklistCheckPort.CheckCommand(
                                "B-CONCURRENT", 1, null, "并发供应商", "CONCURRENT-CODE"));
                    }))
                    .toList();
            ready.await();
            start.countDown();

            List<BlacklistCheckPort.CheckOutcome> outcomes = new java.util.ArrayList<>();
            for (Future<BlacklistCheckPort.CheckOutcome> future : futures) {
                outcomes.add(future.get());
            }

            assertThat(outcomes).allMatch(BlacklistCheckPort.CheckOutcome::matched);
            assertThat(outcomes.stream().filter(outcome -> outcome.persistedAlert() != null)).hasSize(1);
            assertThat(alerts.findAll(null)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
