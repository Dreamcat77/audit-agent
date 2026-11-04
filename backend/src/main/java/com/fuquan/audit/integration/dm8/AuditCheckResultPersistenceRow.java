package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record AuditCheckResultPersistenceRow(
        String id,
        String taskId,
        String ruleCode,
        String itemKey,
        String checkStatus,
        String riskLevel,
        int blockSign,
        String actualValue,
        String expectedValue,
        String detail,
        long ruleVersion,
        LocalDateTime createdAt) {
}
