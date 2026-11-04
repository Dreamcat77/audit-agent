package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.Objects;
import java.util.UUID;

public record RoleConfirmationCommand(
        UUID projectId,
        UUID fileId,
        UUID assessmentId,
        DocumentRole selectedRole,
        long expectedVersion,
        String reason,
        String idempotencyKey,
        ProjectRequestMetadata metadata) {

    public RoleConfirmationCommand {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(assessmentId, "assessmentId must not be null");
        Objects.requireNonNull(selectedRole, "selectedRole must not be null");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
