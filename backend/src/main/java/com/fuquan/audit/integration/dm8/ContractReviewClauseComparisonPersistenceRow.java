package com.fuquan.audit.integration.dm8;

public record ContractReviewClauseComparisonPersistenceRow(String comparisonId, String findingId,
        String runId, String stepId, String attemptId, String comparisonKey, String kind,
        String result, int schemaVersion) { }
