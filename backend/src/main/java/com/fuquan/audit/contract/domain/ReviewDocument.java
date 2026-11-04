package com.fuquan.audit.contract.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Append-only 文件元数据及其 durable 接入状态。 */
public record ReviewDocument(
        UUID id,
        UUID sessionId,
        ReviewDocumentKind documentKind,
        String slotKey,
        String originalFileName,
        String fileExtension,
        String contentType,
        long fileSize,
        String sha256,
        String storageKey,
        ReviewDocumentStatus status,
        UUID replacesDocumentId,
        long requestedSessionVersion,
        long reservedSessionVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant storedAt,
        Instant failedAt,
        String createdBy,
        long versionNo) {

    public ReviewDocument {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(documentKind, "documentKind must not be null");
        slotKey = required(slotKey, "slotKey", 128);
        originalFileName = required(originalFileName, "originalFileName", 256);
        fileExtension = required(fileExtension, "fileExtension", 16).toLowerCase(Locale.ROOT);
        contentType = required(contentType, "contentType", 128).toLowerCase(Locale.ROOT);
        sha256 = required(sha256, "sha256", 64).toLowerCase(Locale.ROOT);
        storageKey = required(storageKey, "storageKey", 256);
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        createdBy = required(createdBy, "createdBy", 36);
        if (fileSize <= 0 || sha256.length() != 64) {
            throw new IllegalArgumentException("file metadata is invalid");
        }
        if (requestedSessionVersion < 0
                || reservedSessionVersion != requestedSessionVersion + 1
                || versionNo < 0) {
            throw new IllegalArgumentException("document versions are invalid");
        }
        if (status == ReviewDocumentStatus.UPLOAD_PENDING && (storedAt != null || failedAt != null)) {
            throw new IllegalArgumentException("pending document cannot be terminal");
        }
        if (status == ReviewDocumentStatus.AVAILABLE && (storedAt == null || failedAt != null)) {
            throw new IllegalArgumentException("available document requires storedAt only");
        }
        if (status == ReviewDocumentStatus.UPLOAD_FAILED && (failedAt == null || storedAt != null)) {
            throw new IllegalArgumentException("failed document requires failedAt only");
        }
    }

    public static ReviewDocument pending(
            UUID id, UUID sessionId, ReviewDocumentKind kind, String slotKey,
            String fileName, String extension, String contentType, long size, String sha256,
            String storageKey, UUID replacesDocumentId, long requestedVersion,
            String createdBy, Instant now) {
        return new ReviewDocument(
                id, sessionId, kind, slotKey, fileName, extension, contentType, size, sha256,
                storageKey, ReviewDocumentStatus.UPLOAD_PENDING, replacesDocumentId,
                requestedVersion, requestedVersion + 1, now, now, null, null, createdBy, 0);
    }

    public ReviewDocument markAvailable(Instant now) {
        requirePending();
        return new ReviewDocument(
                id, sessionId, documentKind, slotKey, originalFileName, fileExtension,
                contentType, fileSize, sha256, storageKey, ReviewDocumentStatus.AVAILABLE,
                replacesDocumentId, requestedSessionVersion, reservedSessionVersion,
                createdAt, now, now, null, createdBy, versionNo + 1);
    }

    public ReviewDocument markFailed(Instant now) {
        requirePending();
        return new ReviewDocument(
                id, sessionId, documentKind, slotKey, originalFileName, fileExtension,
                contentType, fileSize, sha256, storageKey, ReviewDocumentStatus.UPLOAD_FAILED,
                replacesDocumentId, requestedSessionVersion, reservedSessionVersion,
                createdAt, now, null, now, createdBy, versionNo + 1);
    }

    private void requirePending() {
        if (status != ReviewDocumentStatus.UPLOAD_PENDING) {
            throw new IllegalStateException("document is not upload pending");
        }
    }

    private static String required(String value, String field, int max) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
