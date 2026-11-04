package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ContractFactExtractionCandidateRow(String candidateId, String attemptId,
        String candidateKey, String factType, String groupKey, String valueText,
        BigDecimal valueDecimal, String segmentId, String sourceLocator, String excerpt,
        int excerptStart, int excerptEnd, String contextCode, BigDecimal confidence,
        String validationStatus, String validationReason, String publishedExtractionId,
        LocalDateTime createdAt) { }
