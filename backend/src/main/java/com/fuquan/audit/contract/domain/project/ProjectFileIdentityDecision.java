package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only human Project identity resolution, not a machine assessment status. */
public record ProjectFileIdentityDecision(
        UUID decisionId, UUID projectId, long projectVersionNo, UUID fileId,
        String fileSha256, UUID parseResultId, int parseVersion, UUID assessmentId,
        ProjectIdentityResolution resolution, String decidedBy, Instant decidedAt,
        UUID supersedesDecisionId, String reason, long versionNo) {
    public ProjectFileIdentityDecision {
        Objects.requireNonNull(decisionId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(fileId);
        Objects.requireNonNull(parseResultId);
        Objects.requireNonNull(assessmentId);
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(decidedAt);
        if (projectVersionNo <= 0 || parseVersion <= 0 || versionNo <= 0)
            throw new IllegalArgumentException("human identity decision requires positive versions");
        if (fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid file SHA-256");
        if (decidedBy == null || decidedBy.isBlank() || decidedBy.length() > 36)
            throw new IllegalArgumentException("invalid human actor ID");
        if (decisionId.equals(supersedesDecisionId))
            throw new IllegalArgumentException("decision cannot supersede itself");
        if (reason != null && (reason.isBlank() || reason.length() > 500))
            throw new IllegalArgumentException("invalid bounded reason");
    }
}
