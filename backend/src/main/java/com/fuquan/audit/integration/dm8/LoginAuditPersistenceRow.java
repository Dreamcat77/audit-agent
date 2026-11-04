package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record LoginAuditPersistenceRow(
        String id,
        String userId,
        String username,
        String eventType,
        String result,
        String failReason,
        String requestId,
        String traceId,
        String sourceIp,
        LocalDateTime occurredAt) {
}
