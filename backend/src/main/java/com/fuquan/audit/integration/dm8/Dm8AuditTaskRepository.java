package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.application.port.AuditTaskReadQueryPort;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8AuditTaskRepository implements AuditTaskRepository, AuditTaskReadQueryPort {

    private final AuditTaskMapper mapper;

    public Dm8AuditTaskRepository(AuditTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditTask create(AuditTask task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            if (mapper.insert(toRow(task)) != 1) {
                throw new AuditTaskConcurrencyException("audit task insert did not affect one row");
            }
        } catch (DuplicateKeyException exception) {
            throw new AuditTaskConcurrencyException("audit task already exists");
        }
        return task;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditTask> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(mapper.findById(id.toString())).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
        return Optional.ofNullable(mapper.findLatest(sourceType, sourceId.toString(), reviewType))
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page findLatest(String sourceType, String reviewType, int page, int size) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        if (page < 1 || size < 1) throw new IllegalArgumentException("invalid audit task page");
        int offset = (page - 1) * size;
        return new Page(
                mapper.findLatestPage(sourceType, reviewType, offset, offset + size).stream()
                        .map(this::toDomain)
                        .toList(),
                page,
                size,
                mapper.countLatest(sourceType, reviewType));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<AuditTask> findAllLatest(String sourceType, String reviewType) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        return mapper.findAllLatest(sourceType, reviewType).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page findAllPage(String sourceType, String reviewType, int page, int size) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        if (page < 1 || size < 1) throw new IllegalArgumentException("invalid audit task page");
        int offset = (page - 1) * size;
        return new Page(mapper.findAllPage(sourceType, reviewType, offset, offset + size).stream()
                .map(this::toDomain).toList(), page, size, mapper.countAll(sourceType, reviewType));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<AuditTask> findAll(String sourceType, String reviewType) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        return mapper.findAll(sourceType, reviewType).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditTask transition(
            UUID id,
            AuditTaskStatus expectedStatus,
            long expectedVersion,
            AuditTaskStatus targetStatus,
            Instant occurredAt) {
        if (!expectedStatus.canTransitionTo(targetStatus)) {
            throw new AuditTaskConcurrencyException(
                    "illegal audit task transition: " + expectedStatus + " -> " + targetStatus);
        }
        int affected = mapper.transition(
                id.toString(), expectedStatus.name(), expectedVersion, targetStatus.name(), toUtc(occurredAt));
        if (affected != 1) {
            throw new AuditTaskConcurrencyException("audit task state or version conflict");
        }
        return findById(id).orElseThrow(() ->
                new IllegalStateException("transitioned audit task is missing"));
    }

    private AuditTaskPersistenceRow toRow(AuditTask task) {
        AuditTaskPersistenceRow row = new AuditTaskPersistenceRow();
        row.setId(task.id().toString());
        row.setSourceType(task.sourceType());
        row.setSourceId(task.sourceId().toString());
        row.setSourceVersion(task.sourceVersion());
        row.setReviewType(task.reviewType());
        row.setStatus(task.status().name());
        row.setCreatedBy(task.createdBy());
        row.setRequestId(task.requestId());
        row.setTraceId(task.traceId());
        row.setCreatedAt(toUtc(task.createdAt()));
        row.setStartedAt(toUtcNullable(task.startedAt()));
        row.setFinishedAt(toUtcNullable(task.finishedAt()));
        row.setVersionNo(task.versionNo());
        return row;
    }

    private AuditTask toDomain(AuditTaskPersistenceRow row) {
        return new AuditTask(
                UUID.fromString(row.getId()), row.getSourceType(), UUID.fromString(row.getSourceId()),
                row.getSourceVersion(), row.getReviewType(), AuditTaskStatus.valueOf(row.getStatus()),
                row.getCreatedBy(), row.getRequestId(), row.getTraceId(), toInstant(row.getCreatedAt()),
                toInstant(row.getStartedAt()), toInstant(row.getFinishedAt()), row.getVersionNo());
    }

    private LocalDateTime toUtc(Instant value) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(value, "instant must not be null"), ZoneOffset.UTC);
    }

    private LocalDateTime toUtcNullable(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
