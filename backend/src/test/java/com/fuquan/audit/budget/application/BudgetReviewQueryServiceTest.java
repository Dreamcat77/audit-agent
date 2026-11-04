package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewItemRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewTaskRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetValidationConfirmLogRepository;
import com.fuquan.audit.common.exception.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetReviewQueryServiceTest {

    private InMemoryBudgetReviewTaskRepository tasks;
    private InMemoryBudgetReviewItemRepository items;
    private BudgetReviewQueryService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryBudgetReviewTaskRepository();
        items = new InMemoryBudgetReviewItemRepository();
        service = new BudgetReviewQueryService(
                tasks, items, new InMemoryBudgetValidationConfirmLogRepository());
    }

    @Test
    void itemMustBelongToRequestedTask() {
        UUID firstTaskId = UUID.randomUUID();
        UUID secondTaskId = UUID.randomUUID();
        BudgetReviewTask firstTask = task(firstTaskId);
        BudgetReviewTask secondTask = task(secondTaskId);
        tasks.save(firstTask);
        tasks.save(secondTask);
        UUID itemId = UUID.randomUUID();
        items.saveAll(java.util.List.of(item(itemId, firstTaskId)));

        assertThat(service.findItem(firstTaskId, itemId).id()).isEqualTo(itemId);
        assertThatThrownBy(() -> service.findItem(secondTaskId, itemId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预算审查明细不存在");
    }

    private static BudgetReviewTask task(UUID id) {
        Instant now = Instant.now();
        return new BudgetReviewTask(
                id, "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                1L, null, "PARSED", 1, null, "2026", "PROCUREMENT", now, now);
    }

    @Test
    void historyFiltersAllRowsBeforePaginationAndTreatsWildcardsLiterally() {
        var filter = com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of("设备_100%", java.time.LocalDate.of(2026, 10, 14), java.time.LocalDate.of(2026, 10, 14));
        Instant start = Instant.parse("2026-10-13T16:00:00Z");
        saveHistory("设备_100%.xlsx", start.minusNanos(1));
        var first = saveHistory("设备_100%.XLSX", start);
        var last = saveHistory("采购设备_100%.xlsx", Instant.parse("2026-10-14T15:59:59.999Z"));
        saveHistory("设备_100%.xlsx", Instant.parse("2026-10-14T16:00:00Z"));
        saveHistory("设备A100B.xlsx", start);
        assertThat(service.countHistory(null, null, null, filter)).isEqualTo(2);
        assertThat(service.findHistoryPage(null, null, null, filter, 1, 1)).extracting(BudgetReviewTask::id).containsExactly(last.id());
        assertThat(service.findHistoryPage(null, null, null, filter, 2, 1)).extracting(BudgetReviewTask::id).containsExactly(first.id());
        assertThat(service.countHistory(null, "2025", null, filter)).isZero();
        assertThat(service.countHistory(null, null, null,
                com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of("xlsx", null, null))).isEqualTo(5);
    }

    @Test
    void invalidHistoryRangesFailClosedAndTieOrderingIsStable() {
        assertThatThrownBy(() -> com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(null,
                java.time.LocalDate.of(2026, 10, 15), java.time.LocalDate.of(2026, 10, 14)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("开始日期不能晚于结束日期");
        assertThatThrownBy(() -> com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of("x".repeat(256), null, null))
                .isInstanceOf(BusinessException.class);
        var a = saveHistory("same.xlsx", Instant.parse("2026-10-14T00:00:00Z"));
        var b = saveHistory("same.xlsx", a.createdTime());
        var filter = com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(null, null, null);
        var expected = java.util.stream.Stream.of(a.id(), b.id()).sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(service.findHistoryPage(null, null, null, filter, 1, 20)).extracting(BudgetReviewTask::id).containsExactlyElementsOf(expected);
    }

    private BudgetReviewTask saveHistory(String name, Instant time) {
        var task = new BudgetReviewTask(UUID.randomUUID(), name, "application/octet-stream", 1L, null,
                "REVIEWED", 0, null, "2026", "PROCUREMENT", time, time);
        tasks.save(task); return task;
    }

    private static BudgetReviewItem item(UUID id, UUID taskId) {
        Instant now = Instant.now();
        return new BudgetReviewItem(
                id, taskId, 1, "办公设备", null, "办公设备", null,
                "MATCHED", "EXACT", 0, "PASSED", null, null, null, now);
    }
}
