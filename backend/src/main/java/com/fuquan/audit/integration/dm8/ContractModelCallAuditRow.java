package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractModelCallAuditRow(String callAuditId, String factExtractionAttemptId,
        String reviewStepAttemptId, int attemptNo, String scope, int callOrdinal, String phase,
        String requestFingerprint, String provider, String modelName, String outcome,
        String safeReason, long durationMs, String finishReason, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, String batchStatus,
        LocalDateTime createdAt) { }
