package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8AuditTaskRepositoryTest {

    @Mock
    private AuditTaskMapper mapper;

    private Dm8AuditTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new Dm8AuditTaskRepository(mapper);
    }

    @Test
    void createInsertsFullTaskCorrelationAndVersion() {
        AuditTask task = pendingTask();
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        assertThat(repository.create(task)).isSameAs(task);

        verify(mapper).insert(argThat(row ->
                row.getId().equals(task.id().toString())
                        && row.getSourceId().equals(task.sourceId().toString())
                        && row.getStatus().equals("PENDING")
                        && row.getRequestId().equals(task.requestId())
                        && row.getVersionNo() == 0));
    }

    @Test
    void duplicateTaskInsertIsTranslatedToPortConcurrencyConflict() {
        AuditTask task = pendingTask();
        when(mapper.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("synthetic database detail"));

        assertThatThrownBy(() -> repository.create(task))
                .isInstanceOf(AuditTaskConcurrencyException.class)
                .hasMessage("audit task already exists")
                .hasMessageNotContaining("synthetic database detail");
    }

    @Test
    void transitionUsesExpectedStatusAndVersionAndMapsUpdatedTask() {
        AuditTask pending = pendingTask();
        Instant occurredAt = Instant.parse("2026-08-17T01:01:00Z");
        AuditTaskPersistenceRow updated = row(pending, "PROCESSING", 1);
        updated.setStartedAt(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        when(mapper.transition(
                pending.id().toString(), "PENDING", 0, "PROCESSING",
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC))).thenReturn(1);
        when(mapper.findById(pending.id().toString())).thenReturn(updated);

        AuditTask processing = repository.transition(
                pending.id(), AuditTaskStatus.PENDING, 0, AuditTaskStatus.PROCESSING, occurredAt);

        assertThat(processing.status()).isEqualTo(AuditTaskStatus.PROCESSING);
        assertThat(processing.versionNo()).isEqualTo(1);
        assertThat(processing.startedAt()).isEqualTo(occurredAt);
    }

    @Test
    void zeroRowConditionalTransitionIsExplicitConcurrencyConflict() {
        AuditTask task = pendingTask();
        Instant occurredAt = Instant.parse("2026-08-17T01:01:00Z");
        when(mapper.transition(
                task.id().toString(), "PENDING", 0, "PROCESSING",
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC))).thenReturn(0);

        assertThatThrownBy(() -> repository.transition(
                task.id(), AuditTaskStatus.PENDING, 0, AuditTaskStatus.PROCESSING, occurredAt))
                .isInstanceOf(AuditTaskConcurrencyException.class);
    }

    @Test
    void findExistingTaskMapsFullDomain() {
        AuditTask task = pendingTask();
        when(mapper.findById(task.id().toString())).thenReturn(row(task, "PENDING", 0));

        assertThat(repository.findById(task.id())).contains(task);
    }

    @Test
    void legacyTaskStatusRemainsReadableWithoutBecomingANewTransitionTarget() {
        AuditTask task = pendingTask();
        when(mapper.findById(task.id().toString())).thenReturn(row(task, "REVIEWING", 7));

        assertThat(repository.findById(task.id())).get().satisfies(legacy -> {
            assertThat(legacy.status()).isEqualTo(AuditTaskStatus.REVIEWING);
            assertThat(legacy.status().canTransitionTo(AuditTaskStatus.DONE)).isFalse();
        });
    }

    private AuditTask pendingTask() {
        return AuditTask.pending(
                UUID.fromString("39a6539e-a6c0-4ec8-8e4b-2416c180271b"),
                "CONTRACT",
                UUID.fromString("49a6539e-a6c0-4ec8-8e4b-2416c180271b"),
                3,
                "F02_DETERMINISTIC",
                "user-1",
                "request-1",
                "0123456789abcdef0123456789abcdef",
                Instant.parse("2026-08-17T01:00:00Z"));
    }

    private AuditTaskPersistenceRow row(AuditTask task, String status, long version) {
        AuditTaskPersistenceRow row = new AuditTaskPersistenceRow();
        row.setId(task.id().toString());
        row.setSourceType(task.sourceType());
        row.setSourceId(task.sourceId().toString());
        row.setSourceVersion(task.sourceVersion());
        row.setReviewType(task.reviewType());
        row.setStatus(status);
        row.setCreatedBy(task.createdBy());
        row.setRequestId(task.requestId());
        row.setTraceId(task.traceId());
        row.setCreatedAt(LocalDateTime.ofInstant(task.createdAt(), ZoneOffset.UTC));
        row.setVersionNo(version);
        return row;
    }
}
