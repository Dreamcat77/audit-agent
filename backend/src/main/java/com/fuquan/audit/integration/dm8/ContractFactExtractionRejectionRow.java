package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractFactExtractionRejectionRow(String rejectionId, String attemptId,
        int attemptNo, String scope, int providerOrdinal, String stage, String reasonCode,
        String safeShapeHash, LocalDateTime createdAt) { }
