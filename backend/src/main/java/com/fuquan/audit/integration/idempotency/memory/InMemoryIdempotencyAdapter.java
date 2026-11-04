package com.fuquan.audit.integration.idempotency.memory;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyConcurrencyException;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class InMemoryIdempotencyAdapter implements IdempotencyPort {

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public IdempotencyClaim claim(IdempotencyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        IdempotencyRecord candidate = new IdempotencyRecord(
                UUID.randomUUID(), request.scope(), request.idempotencyKey(), request.actorId(),
                request.resourceType(), request.resourceId(), request.requestFingerprint(),
                IdempotencyStatus.IN_PROGRESS, request.resultResourceType(), request.resultResourceId(),
                request.firstRequestId(), request.traceId(),
                request.createdAt(), null, 0);
        IdempotencyRecord existing = records.putIfAbsent(key(request.scope(), request.idempotencyKey()), candidate);
        return existing == null
                ? new IdempotencyClaim(true, candidate)
                : new IdempotencyClaim(false, existing);
    }

    @Override
    public IdempotencyRecord markSucceeded(
            IdempotencyRecord record,
            String resultResourceType,
            String resultResourceId,
            Instant completedAt) {
        return complete(record, IdempotencyStatus.SUCCEEDED, resultResourceType, resultResourceId, completedAt);
    }

    @Override
    public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
        return complete(
                record,
                IdempotencyStatus.FAILED,
                record.resultResourceType(),
                record.resultResourceId(),
                completedAt);
    }

    public int size() {
        return records.size();
    }

    @Override
    public Optional<IdempotencyRecord> findByScopeAndKey(String scope, String idempotencyKey) {
        return Optional.ofNullable(records.get(key(scope, idempotencyKey)));
    }

    @Override
    public Optional<IdempotencyRecord> findByResultResource(
            String resultResourceType, String resultResourceId) {
        return records.values().stream()
                .filter(record -> Objects.equals(record.resultResourceType(), resultResourceType)
                        && Objects.equals(record.resultResourceId(), resultResourceId))
                .findFirst();
    }

    @Override
    public List<IdempotencyRecord> findAllByResultResource(
            String resultResourceType, String resultResourceId) {
        return records.values().stream()
                .filter(record -> Objects.equals(record.resultResourceType(), resultResourceType)
                        && Objects.equals(record.resultResourceId(), resultResourceId))
                .toList();
    }

    @Override
    public void rollbackUncommittedClaim(IdempotencyRecord expected) {
        Objects.requireNonNull(expected, "record must not be null");
        records.computeIfPresent(key(expected.scope(), expected.idempotencyKey()), (key, current) ->
                current.id().equals(expected.id())
                                && current.versionNo() == expected.versionNo()
                                && current.status() == IdempotencyStatus.IN_PROGRESS
                        ? null : current);
    }

    private IdempotencyRecord complete(
            IdempotencyRecord expected,
            IdempotencyStatus status,
            String resultResourceType,
            String resultResourceId,
            Instant completedAt) {
        Objects.requireNonNull(expected, "record must not be null");
        return records.compute(key(expected.scope(), expected.idempotencyKey()), (key, current) -> {
            if (current == null
                    || current.status() != IdempotencyStatus.IN_PROGRESS
                    || current.versionNo() != expected.versionNo()
                    || !current.id().equals(expected.id())) {
                throw new IdempotencyConcurrencyException("idempotency status or version conflict");
            }
            return new IdempotencyRecord(
                    current.id(), current.scope(), current.idempotencyKey(), current.actorId(),
                    current.resourceType(), current.resourceId(), current.requestFingerprint(), status,
                    resultResourceType, resultResourceId, current.firstRequestId(), current.traceId(),
                    current.createdAt(), completedAt, current.versionNo() + 1);
        });
    }

    private String key(String scope, String idempotencyKey) {
        return scope + '\u0000' + idempotencyKey;
    }
}
