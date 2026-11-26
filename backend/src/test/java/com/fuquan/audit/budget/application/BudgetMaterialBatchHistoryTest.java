package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetMaterialBatchHistoryTest {
    final BudgetMaterialBatchRepository batches = mock(BudgetMaterialBatchRepository.class);
    final BudgetMaterialJobRepository jobs = mock(BudgetMaterialJobRepository.class);
    final BudgetReviewTaskRepository tasks = mock(BudgetReviewTaskRepository.class);
    final BudgetMaterialBatchService service = new BudgetMaterialBatchService(batches, jobs, tasks,
            mock(BudgetMaterialBatchTransactions.class), mock(BudgetUploadedMaterialAnalyzer.class), new ObjectMapper());
    final CurrentUser actor = new CurrentUser("owner", "owner", "合成用户", Set.of("BUDGET_USER"));
    final Instant now = Instant.now();
    final UUID id = UUID.randomUUID();
    final BudgetMaterialBatch.Member first = new BudgetMaterialBatch.Member(UUID.randomUUID(), "申报.txt", 10);
    final BudgetMaterialBatch.Member second = new BudgetMaterialBatch.Member(UUID.randomUUID(), "批复.txt", 20);

    BudgetMaterialBatch batch(String status) {
        return new BudgetMaterialBatch(id, actor.userId(), "2026", "PROCUREMENT", List.of(first, second),
                status, null, null, now);
    }
    void historyBatch(BudgetMaterialBatch batch) {
        when(batches.find(id)).thenReturn(Optional.of(batch));
        when(batches.findHistoryPage(eq(actor.userId()), any(), anyInt(), anyInt())).thenReturn(
                List.of(BudgetMaterialBatchRepository.HistoryReference.batch(id, now)));
        when(batches.countHistory(eq(actor.userId()), any())).thenReturn(1L);
    }

    @Test void registeredBatchStaysProcessingBeforeAllFilesArriveAndReopensWithFullManifest() {
        historyBatch(batch("OPEN"));
        var history = service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20);
        assertThat(history.records()).hasSize(1);
        assertThat(history.records().get(0).status()).isEqualTo("OPEN");
        assertThat(history.records().get(0).fileCount()).isEqualTo(2);
        var detail = service.view(id, actor);
        assertThat(detail.files()).extracting(BudgetMaterialBatchService.BatchFile::fileName)
                .containsExactly("申报.txt", "批复.txt");
        assertThat(detail.files()).allMatch(file -> file.taskId() == null && file.status().equals("WAITING_UPLOAD"));
    }

    @Test void oldManifestWithoutJobsIsUploadIncompleteInHistoryAndDetail() {
        var old = new BudgetMaterialBatch(id, actor.userId(), "2026", "PROCUREMENT", List.of(first, second),
                "OPEN", null, null, Instant.now().minusSeconds(25 * 3600));
        historyBatch(old);
        assertThat(service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20).records().get(0).status()).isEqualTo("UPLOAD_INCOMPLETE");
        assertThat(service.view(id, actor).status()).isEqualTo("UPLOAD_INCOMPLETE");
        verifyNoInteractions(tasks);
    }

    @Test void oldBatchWithAnyExistingJobNeverExpiresBasedOnManifestAge() {
        var old = new BudgetMaterialBatch(id, actor.userId(), "2026", "PROCUREMENT", List.of(first, second),
                "OPEN", null, null, Instant.now().minusSeconds(25 * 3600));
        historyBatch(old);
        when(jobs.operation(actor.userId(), first.operationKey().toString())).thenReturn(Optional.of(
                new BudgetMaterialJob(UUID.randomUUID(), actor.userId(), first.operationKey().toString(), "", "PROCESSING", null, null, Instant.now())));
        assertThat(service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20).records().get(0).status()).isEqualTo("OPEN");
    }

    @Test void unknownIsNotConvertedToFailedWhenEveryFileLacksAResult() {
        historyBatch(batch("REVIEWED"));
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        when(batches.sources(id)).thenReturn(List.of(new BudgetMaterialBatch.Source(one, id, null, null),
                new BudgetMaterialBatch.Source(two, id, null, null)));
        when(tasks.findById(one)).thenReturn(Optional.of(new BudgetReviewTask(one, "申报.txt", "text/plain", 10,
                null, "FAILED", 0, null, now, now)));
        when(tasks.findById(two)).thenReturn(Optional.of(new BudgetReviewTask(two, "批复.txt", "text/plain", 20,
                null, "UNKNOWN", 0, null, now, now)));
        assertThat(service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20)
                .records().get(0).status()).isEqualTo("UNKNOWN");
    }

    @Test void completedBatchWithMissingDurableMembershipFailsClosed() {
        historyBatch(batch("REVIEWED"));
        when(batches.sources(id)).thenReturn(List.of());
        assertThat(service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20)
                .records().get(0).status()).isEqualTo("UNKNOWN");
    }

    @Test void successfulAndFailedMembersProducePartialHistory() {
        historyBatch(batch("REVIEWED"));
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        when(batches.sources(id)).thenReturn(List.of(new BudgetMaterialBatch.Source(one, id, null, null),
                new BudgetMaterialBatch.Source(two, id, null, null)));
        when(tasks.findById(one)).thenReturn(Optional.of(new BudgetReviewTask(one, "申报.txt", "text/plain", 10,
                null, "REVIEWED", 1, null, now, now)));
        when(tasks.findById(two)).thenReturn(Optional.of(new BudgetReviewTask(two, "批复.txt", "text/plain", 20,
                null, "FAILED", 0, null, now, now)));
        assertThat(service.history(actor, BudgetHistoryFilter.of(null, null, null), 1, 20)
                .records().get(0).status()).isEqualTo("PARTIAL");
    }

    @Test void projectNameSearchFiltersTheDisplayedNameBeforePagination() throws Exception {
        UUID matchingId = UUID.randomUUID();
        UUID bodyOnlyId = UUID.randomUUID();
        var matching = new BudgetMaterialBatch(matchingId, actor.userId(), "2026", null,
                List.of(new BudgetMaterialBatch.Member(UUID.randomUUID(), "预算申报.txt", 20)), "OPEN", null, null, now);
        var bodyOnly = new BudgetMaterialBatch(bodyOnlyId, actor.userId(), "2026", null,
                List.of(new BudgetMaterialBatch.Member(UUID.randomUUID(), "市政项目材料.txt", 20)), "OPEN", null, null, now.minusSeconds(1));
        when(batches.findProjectNameCandidates(actor.userId(), BudgetHistoryFilter.projectName("市政", null, null)))
                .thenReturn(List.of(BudgetMaterialBatchRepository.HistoryReference.batch(matchingId, now),
                        BudgetMaterialBatchRepository.HistoryReference.batch(bodyOnlyId, now.minusSeconds(1))));
        when(batches.find(matchingId)).thenReturn(Optional.of(matching));
        when(batches.find(bodyOnlyId)).thenReturn(Optional.of(bodyOnly));
        when(batches.sources(matchingId)).thenReturn(List.of(source(matchingId, "市政道路建设项目", "")));
        when(batches.sources(bodyOnlyId)).thenReturn(List.of(source(bodyOnlyId, "办公设备采购项目", "市政只是材料正文内容")));

        var result = service.history(actor, BudgetHistoryFilter.projectName("市政", null, null), 1, 1);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).singleElement().satisfies(record ->
                assertThat(record.projectName()).isEqualTo("市政道路建设项目"));
    }

    private BudgetMaterialBatch.Source source(UUID batchId, String projectName, String otherText) throws Exception {
        var row = new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row("row-1", List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(
                        "projectName", projectName, "text/body", projectName, null, null)));
        var snapshot = new BudgetUploadedFacts.Snapshot(List.of(row), List.of(), List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("text/body", otherText)), null, "TEXT");
        return new BudgetMaterialBatch.Source(UUID.randomUUID(), batchId, new ObjectMapper().writeValueAsString(snapshot), null);
    }

    @Test void anotherActorCannotOpenTheBatch() {
        when(batches.find(id)).thenReturn(Optional.of(batch("OPEN")));
        var other = new CurrentUser("other", "other", "另一用户", Set.of("BUDGET_USER"));
        assertThatThrownBy(() -> service.view(id, other))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        verifyNoInteractions(jobs);
    }
}
