package com.fuquan.audit.contract.domain.project;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectFile(
        UUID id,
        UUID projectId,
        UUID parentArchiveFileId,
        String normalizedRelativePath,
        String originalFilename,
        String mediaType,
        ProjectFileCategory category,
        ProjectFileIngestionStatus ingestionStatus,
        ProjectFileParseStatus parseStatus,
        String storageKey,
        String sha256,
        long sizeBytes,
        String parsedText,
        String safeReasonCode,
        Instant createdAt,
        Instant updatedAt,
        ProjectFileParseProvenance parseProvenance) {

    public ProjectFile {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(ingestionStatus, "ingestionStatus must not be null");
        Objects.requireNonNull(parseStatus, "parseStatus must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(parseProvenance, "parseProvenance must not be null");
        if (!storageKey.matches("[0-9a-f]{64}") || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("storage identity must be lowercase SHA-256");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
    }

    public ProjectFile(
            UUID id,
            UUID projectId,
            UUID parentArchiveFileId,
            String normalizedRelativePath,
            String originalFilename,
            String mediaType,
            ProjectFileCategory category,
            ProjectFileIngestionStatus ingestionStatus,
            ProjectFileParseStatus parseStatus,
            String storageKey,
            String sha256,
            long sizeBytes,
            String parsedText,
            String safeReasonCode,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, parentArchiveFileId, normalizedRelativePath, originalFilename, mediaType,
                category, ingestionStatus, parseStatus, storageKey, sha256, sizeBytes, parsedText,
                safeReasonCode, createdAt, updatedAt, ProjectFileParseProvenance.none());
    }

    public boolean isPendingStale(Instant now, Duration pendingRecoveryAge) {
        if (pendingRecoveryAge == null || pendingRecoveryAge.isZero() || pendingRecoveryAge.isNegative()) {
            throw new IllegalArgumentException("pendingRecoveryAge must be positive");
        }
        return ingestionStatus == ProjectFileIngestionStatus.UPLOAD_PENDING
                && !now.isBefore(updatedAt.plus(pendingRecoveryAge));
    }

    public ProjectFile complete(ProjectFileParseStatus parseStatus, String parsedText, String reason, Instant now) {
        return complete(parseStatus, parsedText, reason, ProjectFileParseProvenance.none(), now);
    }

    public ProjectFile complete(ProjectFileParseStatus parseStatus, String parsedText, String reason,
                                ProjectFileParseProvenance provenance, Instant now) {
        return new ProjectFile(id, projectId, parentArchiveFileId, normalizedRelativePath, originalFilename,
                mediaType, category, ProjectFileIngestionStatus.AVAILABLE, parseStatus, storageKey, sha256,
                sizeBytes, parsedText, reason, createdAt, now, provenance);
    }

    public ProjectFile fail(String reason, Instant now) {
        return new ProjectFile(id, projectId, parentArchiveFileId, normalizedRelativePath, originalFilename,
                mediaType, category, ProjectFileIngestionStatus.UPLOAD_FAILED, ProjectFileParseStatus.FAILED,
                storageKey, sha256, sizeBytes, null, reason, createdAt, now,
                ProjectFileParseProvenance.none());
    }
}
