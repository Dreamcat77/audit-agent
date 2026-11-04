package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.util.Objects;
import java.util.UUID;

public record IdentityConfirmationCommand(UUID projectId, UUID fileId, UUID assessmentId,
        ProjectIdentityResolution resolution, long expectedVersion, String reason,
        String idempotencyKey, ProjectRequestMetadata metadata) {
    public IdentityConfirmationCommand {
        Objects.requireNonNull(projectId); Objects.requireNonNull(fileId);
        Objects.requireNonNull(assessmentId); Objects.requireNonNull(resolution);
        Objects.requireNonNull(metadata);
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    }
}
