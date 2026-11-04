package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewFindingPersistenceRow(
        String findingId, String runId, String stepId, String attemptId, int ordinal,
        String findingType, String category, String statusCode, String attentionCode,
        String riskLevel, String summary, String recommendation, int findingSchemaVersion,
        String policyVersion, String source, String modelProvider, String modelName,
        String modelRevision, String promptVersion, String modelSchemaVersion,
        LocalDateTime createdAt, long versionNo) { }
