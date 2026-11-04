package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyConcurrencyException;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8IdempotencyAdapter implements IdempotencyPort {

    private final IdempotencyMapper mapper;

    public Dm8IdempotencyAdapter(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyClaim claim(IdempotencyRequest request) {
        IdempotencyPersistenceRow candidate = new IdempotencyPersistenceRow(
                UUID.randomUUID().toString(), request.scope(), request.idempotencyKey(), request.actorId(),
                request.resourceType(), request.resourceId(), request.requestFingerprint(),
                IdempotencyStatus.IN_PROGRESS.name(), request.resultResourceType(), request.resultResourceId(),
                request.firstRequestId(), request.traceId(), toUtc(request.createdAt()), null, 0);
        try {
            if (mapper.insert(candidate) == 1) {
                return new IdempotencyClaim(true, toDomain(candidate));
            }
        } catch (DuplicateKeyException duplicate) {
            return existingClaimOrRethrow(request, duplicate);
        } catch (DataIntegrityViolationException integrityFailure) {
            return existingClaimOrRethrow(request, integrityFailure);
        }
        IdempotencyPersistenceRow existing = mapper.findByScopeAndKey(
                request.scope(), request.idempotencyKey());
        if (existing == null) {
            throw new IllegalStateException("idempotency unique conflict record is not readable");
        }
        return new IdempotencyClaim(false, toDomain(existing));
    }

    private IdempotencyClaim existingClaimOrRethrow(
            IdempotencyRequest request, DataIntegrityViolationException integrityFailure) {
        IdempotencyPersistenceRow existing = mapper.findByScopeAndKey(
                request.scope(), request.idempotencyKey());
        if (existing == null) {
            throw integrityFailure;
        }
        return new IdempotencyClaim(false, toDomain(existing));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyRecord markSucceeded(
            IdempotencyRecord record,
            String resultResourceType,
            String resultResourceId,
            Instant completedAt) {
        int affected = mapper.markSucceeded(
                record.id().toString(), record.versionNo(), resultResourceType, resultResourceId,
                toUtc(completedAt));
        return afterUpdate(record, affected);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
        int affected = mapper.markFailed(record.id().toString(), record.versionNo(), toUtc(completedAt));
        return afterUpdate(record, affected);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findByScopeAndKey(String scope, String idempotencyKey) {
        return Optional.ofNullable(mapper.findByScopeAndKey(scope, idempotencyKey)).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findByResultResource(
            String resultResourceType, String resultResourceId) {
        return Optional.ofNullable(mapper.findByResultResource(resultResourceType, resultResourceId))
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdempotencyRecord> findAllByResultResource(
            String resultResourceType, String resultResourceId) {
        return mapper.findAllByResultResource(resultResourceType, resultResourceId).stream()
                .map(this::toDomain).toList();
    }

    private IdempotencyRecord afterUpdate(IdempotencyRecord expected, int affected) {
        if (affected != 1) {
            throw new IdempotencyConcurrencyException("idempotency status or version conflict");
        }
        IdempotencyPersistenceRow row = mapper.findById(expected.id().toString());
        if (row == null) {
            throw new IllegalStateException("updated idempotency record is missing");
        }
        return toDomain(row);
    }

    private IdempotencyRecord toDomain(IdempotencyPersistenceRow row) {
        return new IdempotencyRecord(
                UUID.fromString(row.id()), row.scope(), row.idempotencyKey(), row.actorId(),
                row.resourceType(), row.resourceId(), row.requestFingerprint(),
                IdempotencyStatus.valueOf(row.status()), row.resultResourceType(), row.resultResourceId(),
                row.firstRequestId(), row.traceId(), row.createdAt().toInstant(ZoneOffset.UTC),
                row.completedAt() == null ? null : row.completedAt().toInstant(ZoneOffset.UTC),
                row.versionNo());
    }

    private LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
