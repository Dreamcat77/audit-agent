package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectFileIdentityAssessmentPersistenceRow(
        String assessmentId, String projectId, long projectVersionNo, String fileId, String fileSha256,
        String parseResultId, int parseVersion, String assessmentSource, String assessmentVersion,
        String identityPolicyVersion, String status, String normalizedInputFingerprint,
        String safeReasonCode, LocalDateTime createdAt, LocalDateTime completedAt, long versionNo) { }
