package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectExtractionPersistenceRow(
        String id, String projectId, String sourceFileId, String parseResultId,
        String candidateId, String extractionKey,
        String extractionVersion, String factType, String groupKey, String valueText, String normalizedValue,
        BigDecimal valueAmount, String sourceText, Integer pageNumber, Integer paragraphIndex,
        String sourceLocator, LocalDateTime createdAt) {
}
