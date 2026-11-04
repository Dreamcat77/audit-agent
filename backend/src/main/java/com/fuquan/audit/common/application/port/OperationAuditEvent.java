package com.fuquan.audit.common.application.port;

import java.time.Instant;
import java.util.Objects;

public record OperationAuditEvent(
        String eventType,
        String module,
        String action,
        String userId,
        String username,
        String resourceType,
        String resourceId,
        String taskId,
        OperationAuditResultStatus resultStatus,
        String businessResult,
        String actionDesc,
        String detail,
        String requestId,
        String traceId,
        String sourceIp,
        Instant occurredAt,
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

    public OperationAuditEvent(String eventType, String module, String action, String userId, String username,
                               String resourceType, String resourceId, String taskId,
                               OperationAuditResultStatus resultStatus, String businessResult, String actionDesc,
                               String detail, String requestId, String traceId, String sourceIp, Instant occurredAt) {
        this(eventType, module, action, userId, username, resourceType, resourceId, taskId, resultStatus,
                businessResult, actionDesc, detail, requestId, traceId, sourceIp, occurredAt,
                null, null, null, null, null, null, null, null, null, null);
    }

    public OperationAuditEvent {
        eventType = requireNotBlank(eventType, "eventType");
        module = requireNotBlank(module, "module");
        action = requireNotBlank(action, "action");
        Objects.requireNonNull(resultStatus, "resultStatus must not be null");
        actionDesc = requireNotBlank(actionDesc, "actionDesc");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireNotBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
