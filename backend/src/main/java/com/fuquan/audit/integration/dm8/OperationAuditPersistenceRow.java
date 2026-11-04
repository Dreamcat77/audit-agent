package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record OperationAuditPersistenceRow(
        String id,
        String eventType,
        String module,
        String action,
        String userId,
        String username,
        String resourceType,
        String resourceId,
        String taskId,
        String resultStatus,
        String businessResult,
        String actionDesc,
        String detail,
        String requestId,
        String traceId,
        String sourceIp,
        LocalDateTime occurredAt,
        LocalDateTime createdAt,
        String resourceName,
        String beforeData,
        String afterData,
        String failureReason,
        String confirmationFieldCode,
        String confirmationFieldName,
        String confirmationFieldValue,
        String confirmationRulePrompt,
        String confirmationAiPrompt,
        String confirmationNote) {
}
