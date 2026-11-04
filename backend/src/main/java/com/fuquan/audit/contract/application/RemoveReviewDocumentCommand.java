package com.fuquan.audit.contract.application;

import java.util.UUID;

public record RemoveReviewDocumentCommand(
        UUID sessionId,
        UUID documentId,
        long expectedVersion,
        String idempotencyKey,
        ReviewRequestMetadata metadata) {
}
