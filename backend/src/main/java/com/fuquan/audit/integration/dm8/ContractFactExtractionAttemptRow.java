package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractFactExtractionAttemptRow(String attemptId, String projectId, String fileId,
        String fileSha256, String parseResultId, int parseVersion, String canonicalTextSha256,
        String effectiveRole, String roleDecisionId, String identityDecisionId,
        String inputFingerprint, String actorId, String operationKey, String provider,
        String modelName, String modelRevision, String promptVersion, String schemaVersion,
        String status, int callCount, int acceptedCount, int rejectedCount,
        String failureReason, LocalDateTime createdAt, LocalDateTime updatedAt, long versionNo) { }
