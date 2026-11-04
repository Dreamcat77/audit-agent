package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import java.util.Objects;
import java.util.UUID;

public record RoleAssessmentCommand(
        UUID projectId,
        UUID fileId,
        String idempotencyKey,
        ProjectRequestMetadata metadata) {

    public RoleAssessmentCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
