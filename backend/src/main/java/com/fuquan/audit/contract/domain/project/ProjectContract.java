package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectContract(
        UUID projectId,
        UUID contractId,
        UUID candidateId,
        String createdBy,
        Instant createdAt) {

    public ProjectContract {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(contractId, "contractId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        if (createdBy.isBlank()) throw new IllegalArgumentException("createdBy must not be blank");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
