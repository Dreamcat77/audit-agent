package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CreateContractReviewRunCommand(
        UUID projectId, UUID contractId, long expectedContractVersion,
        List<UUID> materialFileIds, String idempotencyKey, ProjectRequestMetadata metadata) {
    public CreateContractReviewRunCommand {
        Objects.requireNonNull(projectId); Objects.requireNonNull(contractId); Objects.requireNonNull(metadata);
        Objects.requireNonNull(materialFileIds); materialFileIds = List.copyOf(materialFileIds);
        if (expectedContractVersion < 0 || materialFileIds.isEmpty()
                || new HashSet<>(materialFileIds).size() != materialFileIds.size()
                || idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("invalid review run request");
    }
}
