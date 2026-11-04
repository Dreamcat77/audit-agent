package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewStepPersistenceRow(
        String stepId, String runId, String stepType, int requiredFlag, String status,
        String stepPolicyVersion, int currentAttemptNo, String outputResourceType,
        String outputResourceId, String safeReasonCode, LocalDateTime startedAt,
        LocalDateTime completedAt, long versionNo) { }
