package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** F07 招标文件上传及文本解析记录。 */
public record ProcurementTenderDocument(
        UUID id,
        UUID projectId,
        String fileName,
        String fileExtension,
        String contentType,
        long fileSize,
        String sha256,
        String storageKey,
        ProcurementTenderDocumentStatus status,
        ProcurementTenderParseMethod parseMethod,
        String parseEngine,
        Double parseConfidence,
        Integer pageCount,
        String parsedContentJson,
        String parseError,
        String createdBy,
        Instant createdTime,
        Instant updatedTime) {

    public ProcurementTenderDocument {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        requireText(fileName, "fileName");
        requireText(fileExtension, "fileExtension");
        requireText(contentType, "contentType");
        requireText(storageKey, "storageKey");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
        if (fileSize <= 0) throw new IllegalArgumentException("fileSize must be positive");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be lowercase SHA-256");
        }
        if (parseConfidence != null
                && (!Double.isFinite(parseConfidence) || parseConfidence < 0 || parseConfidence > 1)) {
            throw new IllegalArgumentException("parseConfidence must be between 0 and 1");
        }
        if (pageCount != null && pageCount < 0) throw new IllegalArgumentException("pageCount must not be negative");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
