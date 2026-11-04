package com.fuquan.audit.review.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An immutable, irreversible acknowledgement of one durable review finding. */
public record FindingAcknowledgement(
        UUID id,
        UUID checkResultId,
        String acknowledgedBy,
        Instant acknowledgedAt,
        String requestId,
        String traceId,
        long ruleVersion) {

    public FindingAcknowledgement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(checkResultId, "checkResultId must not be null");
        Objects.requireNonNull(acknowledgedBy, "acknowledgedBy must not be null");
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        if (ruleVersion < 0) {
            throw new IllegalArgumentException("ruleVersion must be non-negative");
        }
    }
}
