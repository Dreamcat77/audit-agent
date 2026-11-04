package com.fuquan.audit.contract.application.project;

import java.util.Objects;
import java.util.UUID;

public record ConfirmContractCandidateCommand(UUID projectId, UUID candidateId, long expectedVersion,
        ConfirmedContractPayload payload, String idempotencyKey, ProjectRequestMetadata metadata) {
    public ConfirmContractCandidateCommand {
        Objects.requireNonNull(projectId); Objects.requireNonNull(candidateId);
        Objects.requireNonNull(payload); Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(metadata);
        if (expectedVersion < 0 || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("invalid candidate confirmation command");
        }
    }
}
