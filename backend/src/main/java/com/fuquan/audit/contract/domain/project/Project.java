package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Project(
        UUID id,
        String name,
        String projectNo,
        String category,
        String createdBy,
        long versionNo,
        Instant createdAt,
        Instant updatedAt) {

    public Project {
        Objects.requireNonNull(id, "id must not be null");
        createdBy = requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
        name = normalizeNullable(name, 200, "name");
        projectNo = normalizeNullable(projectNo, 100, "projectNo");
        category = normalizeNullable(category, 100, "category");
    }

    public static Project blank(UUID id, String createdBy, Instant now) {
        return new Project(id, null, null, null, createdBy, 0, now, now);
    }

    public boolean metadataConfirmed() {
        return versionNo > 0;
    }

    public Project confirmMetadata(
            long expectedVersion, String name, String projectNo, String category, Instant now) {
        if (expectedVersion != versionNo) {
            throw new ProjectVersionConflictException();
        }
        return new Project(id, name, projectNo, category, createdBy, versionNo + 1, createdAt, now);
    }

    private static String normalizeNullable(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds limit");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
