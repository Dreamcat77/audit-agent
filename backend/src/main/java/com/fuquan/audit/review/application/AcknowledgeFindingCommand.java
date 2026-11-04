package com.fuquan.audit.review.application;

import java.util.Objects;
import java.util.UUID;

/** Request-scoped inputs for creating one immutable finding acknowledgement. */
public record AcknowledgeFindingCommand(
        UUID checkResultId,
        String idempotencyKey,
        String requestId,
        String traceId) {

    public AcknowledgeFindingCommand {
        Objects.requireNonNull(checkResultId, "checkResultId must not be null");
        idempotencyKey = requireNotBlank(idempotencyKey, "idempotencyKey");
        requestId = requireNotBlank(requestId, "requestId");
        traceId = requireNotBlank(traceId, "traceId");
    }

    private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
