package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import java.io.InputStream;
import java.util.UUID;

public record UploadReviewDocumentCommand(
        UUID sessionId,
        ReviewDocumentKind documentKind,
        long expectedVersion,
        String originalFileName,
        String contentType,
        InputStream inputStream,
        String idempotencyKey,
        ReviewRequestMetadata metadata) {
}
