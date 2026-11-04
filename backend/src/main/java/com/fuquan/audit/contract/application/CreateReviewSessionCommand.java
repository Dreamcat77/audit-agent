package com.fuquan.audit.contract.application;

import java.util.UUID;

public record CreateReviewSessionCommand(
        UUID contractId,
        String name,
        String remark,
        String idempotencyKey,
        ReviewRequestMetadata metadata) {
}
