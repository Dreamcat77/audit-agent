package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectPersistenceRow(
        String id, String name, String projectNo, String category, String createdBy,
        long versionNo, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
