package com.fuquan.audit.contract.domain.reviewrun;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Phase 2A manifest entry, not proof that F02 or AI ran. */
public record ContractReviewStep(
        UUID stepId, UUID runId, ContractReviewStepType stepType, boolean required,
        ContractReviewStepStatus status, String stepPolicyVersion, int currentAttemptNo,
        String outputResourceType, UUID outputResourceId, String safeReasonCode,
        Instant startedAt, Instant completedAt, long versionNo) {
    public ContractReviewStep {
        Objects.requireNonNull(stepId); Objects.requireNonNull(runId); Objects.requireNonNull(stepType);
        Objects.requireNonNull(status); Objects.requireNonNull(stepPolicyVersion);
        if (currentAttemptNo < 0 || versionNo < 0) throw new IllegalArgumentException("invalid step versions");
        if (stepType == ContractReviewStepType.MATERIAL_VALIDATION) {
            if (status != ContractReviewStepStatus.COMPLETED || currentAttemptNo != 0
                    || !"RUN_MATERIAL_SNAPSHOT".equals(outputResourceType) || !runId.equals(outputResourceId)
                    || completedAt == null) throw new IllegalArgumentException("invalid material validation manifest");
        } else if (status == ContractReviewStepStatus.PENDING
                && (currentAttemptNo <= 0 || outputResourceType != null || outputResourceId != null
                || startedAt != null || completedAt != null || safeReasonCode != null))
            throw new IllegalArgumentException("pending step must not claim execution output");
    }
}
