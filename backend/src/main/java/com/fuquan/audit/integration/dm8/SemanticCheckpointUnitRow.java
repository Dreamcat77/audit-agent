package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record SemanticCheckpointUnitRow(String unitExecutionId, String planId, String logicalUnitKey,
        int executionNo, int unitOrdinal, String purpose, String attemptId, String inputFingerprint,
        String dependenciesJson, String status, String owner, long fence, LocalDateTime leaseUntil,
        long reservedMs, long durationMs, String resultJson, String resultSha256, String safeReason,
        String callAuditId, String provider, String modelName, String requestFingerprint,
        int callOrdinal, long versionNo, LocalDateTime createdAt, LocalDateTime updatedAt) { }
