package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;

/** One durable step in the procurement review wizard. */
public record ProcurementReviewStep(
        String stepCode,
        int stepOrder,
        String stepName,
        String status,
        Instant completedTime,
        String resultSummary) {

    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    public ProcurementReviewStep {
        if (stepCode == null || stepCode.isBlank()) throw new IllegalArgumentException("stepCode must not be blank");
        if (stepOrder < 1 || stepOrder > 8) throw new IllegalArgumentException("stepOrder must be between 1 and 8");
        if (stepName == null || stepName.isBlank()) throw new IllegalArgumentException("stepName must not be blank");
        if (!PENDING.equals(status) && !IN_PROGRESS.equals(status) && !COMPLETED.equals(status)) {
            throw new IllegalArgumentException("invalid review step status: " + status);
        }
        Objects.requireNonNull(status, "status must not be null");
    }

    public ProcurementReviewStep complete(Instant time, String summary) {
        return new ProcurementReviewStep(stepCode, stepOrder, stepName, COMPLETED, time, summary);
    }

    public ProcurementReviewStep activate() {
        return new ProcurementReviewStep(stepCode, stepOrder, stepName, IN_PROGRESS, null, resultSummary);
    }
}
