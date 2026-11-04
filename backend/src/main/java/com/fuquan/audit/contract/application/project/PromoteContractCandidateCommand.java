package com.fuquan.audit.contract.application.project;

import java.util.Objects;
import java.util.UUID;

public record PromoteContractCandidateCommand(
        UUID projectId,
        UUID candidateId,
        long expectedVersion,
        UUID confirmationId,
        String idempotencyKey,
        ProjectRequestMetadata metadata) {

    public PromoteContractCandidateCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(confirmationId, "confirmationId must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
    }
}
