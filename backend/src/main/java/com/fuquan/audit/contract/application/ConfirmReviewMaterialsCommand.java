package com.fuquan.audit.contract.application;

import java.util.UUID;

public record ConfirmReviewMaterialsCommand(
        UUID sessionId,
        long expectedVersion,
        String idempotencyKey,
        ReviewRequestMetadata metadata) {
}
