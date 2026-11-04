package com.fuquan.audit.contract.application.project;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

public record ProjectFileUploadCommand(
        UUID projectId, String filename, String mediaType, long declaredSize, InputStream input,
        String idempotencyKey, ProjectRequestMetadata metadata) {
    public ProjectFileUploadCommand {
        Objects.requireNonNull(projectId); Objects.requireNonNull(input); Objects.requireNonNull(metadata);
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("filename required");
        if (mediaType == null || mediaType.isBlank()) mediaType = "application/octet-stream";
        if (declaredSize < 0) throw new IllegalArgumentException("declaredSize must be non-negative");
    }
}
