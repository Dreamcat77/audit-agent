package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Parsed bidder document retained for project-level F11 analysis. */
public record ProcurementCollusionDocument(UUID id, UUID projectId, String bidderName, String ipAddress,
        String handlerName, String fileName, String fileExtension, String contentType, long fileSize,
        String sha256, String storageKey, String parsedText, Instant createdTime) {
    public ProcurementCollusionDocument {
        Objects.requireNonNull(id, "id must not be null"); Objects.requireNonNull(projectId, "projectId must not be null");
        if (bidderName == null || bidderName.isBlank()) throw new IllegalArgumentException("bidderName must not be blank");
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName must not be blank");
        if (parsedText == null || parsedText.isBlank()) throw new IllegalArgumentException("parsedText must not be blank");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
    }
}
