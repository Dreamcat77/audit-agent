package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewStepAttemptPersistenceRow(
        String attemptId, String runId, String stepId, int attemptNo, String operationIdentity,
        String status, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime claimedAt,
        String externalCallState, String externalRequestFingerprint, String providerRequestId,
        String provider, String model, LocalDateTime startedAt, LocalDateTime completedAt,
        String safeReasonCode, String outputResourceType, String outputResourceId, long versionNo) { }
