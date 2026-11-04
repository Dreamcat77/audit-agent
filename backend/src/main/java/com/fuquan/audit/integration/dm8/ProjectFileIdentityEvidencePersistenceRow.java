package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectFileIdentityEvidencePersistenceRow(
        String evidenceId, String assessmentId, String projectId, long projectVersionNo,
        String fileId, String fileSha256, String parseResultId, int parseVersion,
        String factType, String extractionResultId, String leftSource, String rightSource,
        String leftNormalizedHash, String rightNormalizedHash, String leftBoundedValue,
        String rightBoundedValue, String comparisonOutcome, String significanceCode,
        String sourceLocator, LocalDateTime createdAt) { }
