package com.fuquan.audit.contract.application.project;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Only user-operation identity; parse, decisions and facts are resolved by the server. */
public record ReplaceContractCandidateSupportAuthorityCommand(
        UUID projectId, UUID candidateId, long expectedCandidateVersion, List<UUID> supportFileIds,
        String idempotencyKey, ProjectRequestMetadata metadata) {
    public ReplaceContractCandidateSupportAuthorityCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        supportFileIds = List.copyOf(Objects.requireNonNull(supportFileIds, "supportFileIds must not be null"));
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
