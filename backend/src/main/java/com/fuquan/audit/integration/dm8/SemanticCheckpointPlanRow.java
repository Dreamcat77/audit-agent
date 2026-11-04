package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record SemanticCheckpointPlanRow(String planId, String runId, String stepId,
        String originalAttemptId, String currentAttemptId, String inputFingerprint,
        String policyFingerprint, String planJson, LocalDateTime deadlineAt, long timeLimitMs,
        int callLimit, int repairLimit, String owner, long fence, LocalDateTime leaseUntil,
        String status, int usedCalls, int reservedCalls, int usedRepairs, int reservedRepairs,
        long usedMs, long reservedMs, String finalResultSha256, long versionNo,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }
