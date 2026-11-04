package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractRuleConfigPersistenceRow(
        String ruleCode,
        String ruleName,
        int enabled,
        String failLevel,
        int blockSign,
        long versionNo,
        String description,
        LocalDateTime updatedAt) {
}
