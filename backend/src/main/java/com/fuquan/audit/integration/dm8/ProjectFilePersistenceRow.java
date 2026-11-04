package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectFilePersistenceRow(
        String id, String projectId, String parentArchiveFileId, String normalizedRelativePath,
        String originalFilename, String mediaType, String category, String ingestionStatus,
        String parseStatus, String storageKey, String sha256, long sizeBytes, String parsedText,
        String parseMethod, String parseEngine, BigDecimal parseConfidence, Integer pageCount,
        Long parseDurationMs, String safeReasonCode, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
