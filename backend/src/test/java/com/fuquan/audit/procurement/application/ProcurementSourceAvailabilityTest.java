package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.material.domain.*;
import com.fuquan.audit.integration.material.memory.InMemoryMaterialIntakeRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.CreateCommand;
import com.fuquan.audit.procurement.infra.memory.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProcurementSourceAvailabilityTest {
    private final InMemoryMaterialIntakeRepository materials = new InMemoryMaterialIntakeRepository();
    private final InMemoryProcurementProjectQueryAdapter projects = new InMemoryProcurementProjectQueryAdapter();
    private final InMemoryProcurementSourceProjectAdapter sources = new InMemoryProcurementSourceProjectAdapter(materials, projects);
    private UUID source(int age) {
        UUID id = UUID.randomUUID(); var now = Instant.now().minusSeconds(age);
        var fields = new MaterialIntakeFields("同名项目", "NO-" + id, null, "货物", null, null, null,
                null, null, null, Map.of(), List.of(), "test", "v1");
        materials.insert(new MaterialIntake(id, "actor", id.toString(), "采购.docx", "docx", 1,
                "a".repeat(64), "b".repeat(64), "PARSED", "lease", now, fields, "DOCX_TEXT", null, now, now));
        return id;
    }
    private CreateCommand command(UUID id) {
        return new CreateCommand(id, "NO", "同名项目", "部门", "货物", new BigDecimal("100"),
                "A1", "设备", "公开招标", null, "申请人", null);
    }
    @Test void filtersBeforeLimitAcrossStatusesAndRetainsDistinctSameNameProjects() {
        var used = source(0); var available = source(1);
        var id = projects.create(command(used));
        projects.updateStatus(id, "APPROVED");
        assertThat(sources.findEligible("actor", null, 1)).extracting(p -> p.id()).containsExactly(available);
        assertThat(sources.findEligible("actor", "同名", 100)).extracting(p -> p.id()).containsExactly(available);
        projects.delete(id);
        assertThat(sources.findEligible("actor", null, 100)).extracting(p -> p.id()).containsExactly(used, available);
    }
    @Test void thirtyTwoCompetingCreatesHaveExactlyOneWinnerAndReadersRemainSafe() throws Exception {
        var source = source(0); var pool = Executors.newFixedThreadPool(32);
        var start = new CountDownLatch(1); var wins = new AtomicInteger(); var conflicts = new AtomicInteger();
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 32; i++) futures.add(pool.submit(() -> {
                try {
                    start.await(); projects.create(command(source)); wins.incrementAndGet();
                } catch (BusinessException e) { conflicts.incrementAndGet(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                for (int n = 0; n < 100; n++) {
                    projects.findPage(null, null, 0, 100); sources.findEligible("actor", null, 100);
                }
            }));
            start.countDown(); for (var f : futures) f.get(15, TimeUnit.SECONDS);
            assertThat(wins.get()).isEqualTo(1); assertThat(conflicts.get()).isEqualTo(31);
            assertThat(sources.findEligible("actor", null, 100)).isEmpty();
            assertThat(java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()).isNull();
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
}
