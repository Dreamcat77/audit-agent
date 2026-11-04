package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record FindingAcknowledgementPersistenceRow(
        String id,
        String checkResultId,
        String acknowledgedBy,
        LocalDateTime acknowledgedAt,
        String requestId,
        String traceId,
        long ruleVersion) {
}
