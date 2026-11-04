package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable main review stages and applicable auxiliary analyses; historic step IDs are preserved. */
public record ProcurementReviewWorkflow(
        UUID id,
        UUID projectId,
        int currentStepOrder,
        String status,
        Instant createdTime,
        Instant updatedTime) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    public ProcurementReviewWorkflow {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (currentStepOrder < 1 || currentStepOrder > 8) {
            throw new IllegalArgumentException("currentStepOrder must be between 1 and 8");
        }
        if (!IN_PROGRESS.equals(status) && !COMPLETED.equals(status)) {
            throw new IllegalArgumentException("invalid workflow status: " + status);
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }
}
