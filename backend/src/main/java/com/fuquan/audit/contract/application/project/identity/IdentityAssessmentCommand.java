package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import java.util.Objects;
import java.util.UUID;

public record IdentityAssessmentCommand(UUID projectId, UUID fileId, String idempotencyKey,
        ProjectRequestMetadata metadata) {
    public IdentityAssessmentCommand {
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(fileId);
        Objects.requireNonNull(metadata);
    }
}
