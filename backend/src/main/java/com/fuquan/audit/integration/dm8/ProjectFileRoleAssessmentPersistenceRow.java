package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectFileRoleAssessmentPersistenceRow(
        String assessmentId, String projectId, String fileId, String fileSha256,
        String parseResultId, int parseVersion, String executionStatus, String proposedRole,
        BigDecimal confidence, boolean conflictFlag, String assessmentSource, String assessmentVersion,
        String rolePolicyVersion, String candidateRolesJson, String precedingDeterministicAssessmentId,
        String precedingAssessmentSource,
        String modelProvider, String modelName, String modelRevision, String promptVersion, String schemaVersion,
        String inputFingerprint, String assessmentFenceKey, String safeReasonCode,
        LocalDateTime createdAt, LocalDateTime startedAt,
        LocalDateTime completedAt, int attemptNo, long versionNo) {
}
