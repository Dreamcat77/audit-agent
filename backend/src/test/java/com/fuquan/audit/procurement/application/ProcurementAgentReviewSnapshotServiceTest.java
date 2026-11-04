package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewSnapshotRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ProcurementAgentReviewSnapshotServiceTest {

    @Test
    void refreshOverwritesTheExistingProjectSnapshotAndFindDoesNotRunReviewAgain() {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(projectId, "BA-TEST", "办公设备采购项目",
                "M1001", "办公设备采购", new BigDecimal("10000"), null, "APPROVED", Instant.now(),
                "采购部", "货物", "询价", "采购办公设备", "张三", null);
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        ProcurementAgentReviewService review = new ProcurementAgentReviewService(
                new ProcurementProjectQueryService(new StubProjectPort(project)));
        ProcurementAgentReviewSnapshotService service = new ProcurementAgentReviewSnapshotService(
                new ProcurementProjectQueryService(new StubProjectPort(project)), review, snapshots,
                new ObjectMapper().registerModule(new JavaTimeModule()));

        ProcurementAgentReviewService.ReviewResult first = service.refresh(projectId);
        ProcurementAgentReviewService.ReviewResult second = service.refresh(projectId);

        assertThat(snapshots.savedCount).isEqualTo(2);
        assertThat(snapshots.value.get().projectId()).isEqualTo(projectId);
        assertThat(service.find(projectId)).contains(second);
        assertThat(snapshots.value.get().reviewJson()).contains("办公设备采购项目");
        assertThat(first.projectId()).isEqualTo(second.projectId());
    }

    @Test
    void deleteRemovesTheProjectSnapshot() {
        UUID projectId = UUID.randomUUID();
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        snapshots.save(new ProcurementAgentReviewSnapshot(UUID.randomUUID(), projectId, Instant.now(), "{}"));

        snapshots.deleteByProjectId(projectId);

        assertThat(snapshots.findByProjectId(projectId)).isEmpty();
    }

    @Test
    void listSummariesReturnsAvailableOnlyForProjectsWithSnapshots() {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(projectId, "BA-TEST", "办公设备采购项目",
                "M1001", "办公设备采购", new BigDecimal("10000"), null, "APPROVED", Instant.now(),
                "采购部", "货物", "询价", "采购办公设备", "张三", null);
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        ProcurementProjectQueryService query = new ProcurementProjectQueryService(new StubProjectPort(project));
        ProcurementAgentReviewSnapshotService service = new ProcurementAgentReviewSnapshotService(
                query, new ProcurementAgentReviewService(query), snapshots,
                new ObjectMapper().registerModule(new JavaTimeModule()));

        service.refresh(projectId);

        assertThat(service.findSummaries(java.util.List.of(projectId, UUID.randomUUID())))
                .containsKey(projectId)
                .extractingByKey(projectId)
                .satisfies(summary -> {
                    assertThat(summary.status()).isEqualTo("AVAILABLE");
                    assertThat(summary.riskLevel()).isEqualTo("INFO");
                    assertThat(summary.reviewTime()).isNotNull();
                });
    }

    @Test
    void snapshotPersistenceFailureLogsNoExceptionMessageOrStack(CapturedOutput output) {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(projectId, "BA-TEST", "办公设备采购项目",
                "M1001", "办公设备采购", new BigDecimal("10000"), null, "APPROVED", Instant.now(),
                "采购部", "货物", "询价", "采购办公设备", "张三", null);
        ProcurementProjectQueryService query = new ProcurementProjectQueryService(new StubProjectPort(project));
        ProcurementAgentReviewSnapshotRepository snapshots = mock(ProcurementAgentReviewSnapshotRepository.class);
        doThrow(new IllegalStateException("TOKEN-CANARY contract-body SQL-values"))
                .when(snapshots).save(any());
        ProcurementAgentReviewSnapshotService service = new ProcurementAgentReviewSnapshotService(
                query, new ProcurementAgentReviewService(query), snapshots,
                new ObjectMapper().registerModule(new JavaTimeModule()));

        assertThatThrownBy(() -> service.refresh(projectId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output).contains("event=PROCUREMENT_SNAPSHOT_SAVE_FAILED")
                .contains("failureType=java.lang.IllegalStateException")
                .doesNotContain("TOKEN-CANARY")
                .doesNotContain("contract-body")
                .doesNotContain("SQL-values");
    }

    private record StubProjectPort(ProcurementProject project) implements ProcurementProjectQueryPort {
        @Override
        public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(java.util.List.of(project), 1);
        }

        @Override
        public Optional<ProcurementProject> findById(UUID id) {
            return project.id().equals(id) ? Optional.of(project) : Optional.empty();
        }
    }

    private static final class InMemorySnapshotRepository implements ProcurementAgentReviewSnapshotRepository {
        private final AtomicReference<ProcurementAgentReviewSnapshot> value = new AtomicReference<>();
        private int savedCount;

        @Override
        public void save(ProcurementAgentReviewSnapshot snapshot) {
            savedCount++;
            value.set(snapshot);
        }

        @Override
        public Optional<ProcurementAgentReviewSnapshot> findByProjectId(UUID projectId) {
            ProcurementAgentReviewSnapshot snapshot = value.get();
            return snapshot != null && snapshot.projectId().equals(projectId)
                    ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public void deleteByProjectId(UUID projectId) {
            value.updateAndGet(snapshot -> snapshot != null && snapshot.projectId().equals(projectId)
                    ? null : snapshot);
        }
    }
}
