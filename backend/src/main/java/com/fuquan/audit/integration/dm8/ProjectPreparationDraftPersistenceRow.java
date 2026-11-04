package com.fuquan.audit.integration.dm8;

import java.time.Instant;

public record ProjectPreparationDraftPersistenceRow(
        String projectId,
        Instant createdAt,
        long uploadedFileCount) {
}
