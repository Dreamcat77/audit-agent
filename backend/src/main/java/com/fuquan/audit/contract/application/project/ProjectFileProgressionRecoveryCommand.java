package com.fuquan.audit.contract.application.project;

import java.util.Objects;
import java.util.UUID;

public record ProjectFileProgressionRecoveryCommand(
        UUID projectId,
        UUID fileId,
        String expectedBlocker,
        String expectedRecoveryInputFingerprint,
        String idempotencyKey,
        ProjectRequestMetadata metadata) {
    public ProjectFileProgressionRecoveryCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        expectedBlocker = required(expectedBlocker, "expectedBlocker");
        expectedRecoveryInputFingerprint = required(
                expectedRecoveryInputFingerprint, "expectedRecoveryInputFingerprint");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
