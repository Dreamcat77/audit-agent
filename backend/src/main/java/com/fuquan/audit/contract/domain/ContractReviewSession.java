package com.fuquan.audit.contract.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 用户发起的一次合同审核材料准备生命周期。 */
public record ContractReviewSession(
        UUID id,
        UUID contractId,
        long contractVersion,
        String name,
        String reviewType,
        ReviewSessionStatus status,
        String remark,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant materialsConfirmedAt,
        long versionNo,
        Instant deletedAt,
        String deletedBy,
        ReviewSessionSourceKind sourceKind,
        UUID projectId) {

    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_REMARK_LENGTH = 1000;

    public ContractReviewSession {
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if ((sourceKind == ReviewSessionSourceKind.PROJECT_FILE_REFERENCE) != (projectId != null)) {
            throw new IllegalArgumentException("source kind and project identity must agree");
        }
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contractId, "contractId must not be null");
        name = normalizeRequired(name, "name", MAX_NAME_LENGTH);
        reviewType = normalizeRequired(reviewType, "reviewType", 64);
        Objects.requireNonNull(status, "status must not be null");
        remark = normalizeOptional(remark, "remark", MAX_REMARK_LENGTH);
        createdBy = normalizeRequired(createdBy, "createdBy", 36);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (contractVersion < 0 || versionNo < 0) {
            throw new IllegalArgumentException("versions must be non-negative");
        }
        if (status == ReviewSessionStatus.PREPARING_MATERIALS && materialsConfirmedAt != null) {
            throw new IllegalArgumentException("preparing session cannot have confirmation time");
        }
        if (status == ReviewSessionStatus.READY && materialsConfirmedAt == null) {
            throw new IllegalArgumentException("ready session requires confirmation time");
        }
        if ((deletedAt == null) != (deletedBy == null)) {
            throw new IllegalArgumentException("deletedAt and deletedBy must be set together");
        }
        if (deletedBy != null) {
            deletedBy = normalizeRequired(deletedBy, "deletedBy", 36);
        }
    }

    public static ContractReviewSession create(
            UUID id,
            UUID contractId,
            long contractVersion,
            String name,
            String reviewType,
            String remark,
            String createdBy,
            Instant now) {
        return new ContractReviewSession(
                id, contractId, contractVersion, name, reviewType,
                ReviewSessionStatus.PREPARING_MATERIALS, remark, createdBy,
                now, now, null, 0, null, null);
    }

    /** Source compatibility for historical rows and callers. Never infers Project ownership. */
    public ContractReviewSession(UUID id, UUID contractId, long contractVersion, String name,
            String reviewType, ReviewSessionStatus status, String remark, String createdBy,
            Instant createdAt, Instant updatedAt, Instant materialsConfirmedAt, long versionNo,
            Instant deletedAt, String deletedBy) {
        this(id, contractId, contractVersion, name, reviewType, status, remark, createdBy,
                createdAt, updatedAt, materialsConfirmedAt, versionNo, deletedAt, deletedBy,
                ReviewSessionSourceKind.LEGACY_REVIEW_DOCUMENT, null);
    }

    public static ContractReviewSession createProjectBacked(UUID id, UUID projectId, UUID contractId,
            long contractVersion, String name, String remark, String actor, Instant now) {
        return new ContractReviewSession(id, contractId, contractVersion, name, "F02_DETERMINISTIC",
                ReviewSessionStatus.PREPARING_MATERIALS, remark, actor, now, now, null, 0, null, null,
                ReviewSessionSourceKind.PROJECT_FILE_REFERENCE, projectId);
    }

    public ContractReviewSession advanceVersion(Instant now) {
        requirePreparing();
        return new ContractReviewSession(
                id, contractId, contractVersion, name, reviewType, status, remark,
                createdBy, createdAt, Objects.requireNonNull(now), null, versionNo + 1,
                deletedAt, deletedBy, sourceKind, projectId);
    }

    public ContractReviewSession confirmMaterials(Instant now) {
        requirePreparing();
        Objects.requireNonNull(now, "confirmation time must not be null");
        return new ContractReviewSession(
                id, contractId, contractVersion, name, reviewType, ReviewSessionStatus.READY,
                remark, createdBy, createdAt, now, now, versionNo + 1, deletedAt, deletedBy, sourceKind, projectId);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    public ContractReviewSession softDelete(String actorId, Instant now) {
        if (deleted()) {
            throw new IllegalStateException("review session is already deleted");
        }
        String actor = normalizeRequired(actorId, "actorId", 36);
        Instant deletedTime = Objects.requireNonNull(now, "deletion time must not be null");
        return new ContractReviewSession(
                id, contractId, contractVersion, name, reviewType, status, remark,
                createdBy, createdAt, deletedTime, materialsConfirmedAt, versionNo + 1,
                deletedTime, actor, sourceKind, projectId);
    }

    private void requirePreparing() {
        if (deleted()) {
            throw new IllegalStateException("deleted review session cannot be changed");
        }
        if (status != ReviewSessionStatus.PREPARING_MATERIALS) {
            throw new IllegalStateException("review session no longer accepts material changes");
        }
    }

    public static String normalizeName(String value) {
        return normalizeRequired(value, "name", MAX_NAME_LENGTH);
    }

    public static String normalizeRemark(String value) {
        return normalizeOptional(value, "remark", MAX_REMARK_LENGTH);
    }

    private static String normalizeRequired(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        return normalized;
    }
}
