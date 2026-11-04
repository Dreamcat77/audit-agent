package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record IdempotencyPersistenceRow(
        String id,
        String scope,
        String idempotencyKey,
        String actorId,
        String resourceType,
        String resourceId,
        String requestFingerprint,
        String status,
        String resultResourceType,
        String resultResourceId,
        String firstRequestId,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        long versionNo) {
}
