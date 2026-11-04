package com.fuquan.audit.common.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyRecord(
        UUID id,
        String scope,
        String idempotencyKey,
        String actorId,
        String resourceType,
        String resourceId,
        String requestFingerprint,
        IdempotencyStatus status,
        String resultResourceType,
        String resultResourceId,
        String firstRequestId,
        String traceId,
        Instant createdAt,
        Instant completedAt,
        long versionNo) {

    public IdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
    }
}
