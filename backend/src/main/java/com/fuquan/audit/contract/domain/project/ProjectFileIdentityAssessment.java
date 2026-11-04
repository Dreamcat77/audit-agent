package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable machine comparison. It never establishes Project ownership. */
public record ProjectFileIdentityAssessment(
        UUID assessmentId, UUID projectId, long projectVersionNo,
        UUID fileId, String fileSha256, UUID parseResultId, int parseVersion,
        ProjectIdentityAssessmentSource assessmentSource, String assessmentVersion,
        String identityPolicyVersion, ProjectIdentityAssessmentStatus status,
        String normalizedInputFingerprint, String safeReasonCode,
        Instant createdAt, Instant completedAt, long versionNo) {
    public ProjectFileIdentityAssessment {
        Objects.requireNonNull(assessmentId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(fileId);
        Objects.requireNonNull(parseResultId);
        Objects.requireNonNull(assessmentSource);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(completedAt);
        if (projectVersionNo < 0 || parseVersion <= 0 || versionNo < 0 || completedAt.isBefore(createdAt))
            throw new IllegalArgumentException("invalid identity assessment version or time");
        if (assessmentSource == ProjectIdentityAssessmentSource.DETERMINISTIC
                && status == ProjectIdentityAssessmentStatus.SAME_PROJECT)
            throw new IllegalArgumentException("automatic SAME_PROJECT is disabled");
        if (fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")
                || normalizedInputFingerprint == null || !normalizedInputFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("identity assessment hashes must be SHA-256");
        if (assessmentVersion == null || assessmentVersion.isBlank() || identityPolicyVersion == null
                || identityPolicyVersion.isBlank()) throw new IllegalArgumentException("identity versions required");
        if (safeReasonCode != null && (safeReasonCode.isBlank() || safeReasonCode.length() > 100))
            throw new IllegalArgumentException("invalid safe reason code");
    }
}
