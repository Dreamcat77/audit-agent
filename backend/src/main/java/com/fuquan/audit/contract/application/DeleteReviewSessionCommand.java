package com.fuquan.audit.contract.application;

import java.util.UUID;

/** ReviewSession durable soft-delete command. */
public record DeleteReviewSessionCommand(
        UUID sessionId,
        long expectedVersion,
        String idempotencyKey,
        ReviewRequestMetadata metadata) {
}
