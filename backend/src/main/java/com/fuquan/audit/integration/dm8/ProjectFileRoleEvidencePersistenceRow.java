package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectFileRoleEvidencePersistenceRow(
        String evidenceId, String assessmentId, String projectId, String fileId, String fileSha256,
        String parseResultId, int parseVersion, String role, String signalType, String normalizedValue,
        String ruleCode, BigDecimal weight, String sourceLocator, int charStart, int charEnd,
        String textHash, String boundedExcerpt, String extractorVersion, LocalDateTime createdAt) {
}
