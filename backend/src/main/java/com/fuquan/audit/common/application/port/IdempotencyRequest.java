package com.fuquan.audit.common.application.port;

import java.time.Instant;
import java.util.Objects;

public record IdempotencyRequest(
        String scope,
        String idempotencyKey,
        String actorId,
        String resourceType,
        String resourceId,
        String requestFingerprint,
        String resultResourceType,
        String resultResourceId,
        String firstRequestId,
        String traceId,
        Instant createdAt) {

    public IdempotencyRequest {
        scope = requireNotBlank(scope, "scope");
        idempotencyKey = requireNotBlank(idempotencyKey, "idempotencyKey");
        actorId = requireNotBlank(actorId, "actorId");
        resourceType = requireNotBlank(resourceType, "resourceType");
        resourceId = requireNotBlank(resourceId, "resourceId");
        requestFingerprint = requireNotBlank(requestFingerprint, "requestFingerprint");
        resultResourceType = requireNotBlank(resultResourceType, "resultResourceType");
        resultResourceId = requireNotBlank(resultResourceId, "resultResourceId");
        firstRequestId = requireNotBlank(firstRequestId, "firstRequestId");
        traceId = requireNotBlank(traceId, "traceId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
