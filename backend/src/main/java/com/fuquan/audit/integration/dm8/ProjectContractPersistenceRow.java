package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectContractPersistenceRow(
        String projectId,
        String contractId,
        String candidateId,
        String createdBy,
        LocalDateTime createdAt) {
}
