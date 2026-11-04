package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.util.Objects;
import java.util.UUID;

public record RetryReviewStepCommand(UUID projectId, UUID contractId, UUID runId, UUID stepId,
        long expectedRunVersion, long expectedStepVersion, UUID previousAttemptId, int previousAttemptNo,
        long previousAttemptVersion, ContractReviewStepStatus previousStatus,
        ReviewStepExternalCallState previousExternalCallState, String reason, String idempotencyKey,
        ProjectRequestMetadata metadata) {
    public RetryReviewStepCommand {
        Objects.requireNonNull(projectId); Objects.requireNonNull(contractId); Objects.requireNonNull(runId);
        Objects.requireNonNull(stepId); Objects.requireNonNull(previousAttemptId); Objects.requireNonNull(previousStatus);
        Objects.requireNonNull(previousExternalCallState); Objects.requireNonNull(metadata);
        if (expectedRunVersion < 0 || expectedStepVersion < 0 || previousAttemptVersion < 0 || previousAttemptNo < 1
                || previousAttemptNo == Integer.MAX_VALUE || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("invalid retry command");
        reason = ReviewStepRetryPolicy.normalizeReason(reason);
    }
}
