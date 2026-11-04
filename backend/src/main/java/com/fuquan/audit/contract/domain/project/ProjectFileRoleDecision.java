package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only authoritative human role decision. */
public record ProjectFileRoleDecision(
        UUID decisionId,
        UUID projectId,
        UUID fileId,
        String fileSha256,
        UUID parseResultId,
        int parseVersion,
        UUID assessmentId,
        DocumentRole selectedRole,
        RoleDecisionStatus decisionStatus,
        String decidedBy,
        Instant decidedAt,
        UUID supersedesDecisionId,
        String reason,
        long versionNo) {

    public ProjectFileRoleDecision {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        if (fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fileSha256 must be lowercase SHA-256");
        }
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        if (parseVersion <= 0) throw new IllegalArgumentException("parseVersion must be positive");
        Objects.requireNonNull(assessmentId, "assessmentId must not be null");
        Objects.requireNonNull(selectedRole, "selectedRole must not be null");
        if (decisionStatus != RoleDecisionStatus.CONFIRMED) {
            throw new IllegalArgumentException("stored human decision must be CONFIRMED");
        }
        if (decidedBy == null || decidedBy.isBlank()) throw new IllegalArgumentException("decidedBy must not be blank");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        if (decisionId.equals(supersedesDecisionId)) throw new IllegalArgumentException("decision cannot supersede itself");
        if (reason != null && reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (versionNo <= 0) throw new IllegalArgumentException("versionNo must be positive");
    }

    public boolean sameStreamFact(ProjectFileRoleDecision other) {
        return projectId.equals(other.projectId) && fileId.equals(other.fileId)
                && fileSha256.equals(other.fileSha256) && parseResultId.equals(other.parseResultId)
                && parseVersion == other.parseVersion && assessmentId.equals(other.assessmentId)
                && selectedRole == other.selectedRole && decisionStatus == other.decisionStatus
                && decidedBy.equals(other.decidedBy) && decidedAt.equals(other.decidedAt)
                && Objects.equals(supersedesDecisionId, other.supersedesDecisionId)
                && Objects.equals(reason, other.reason) && versionNo == other.versionNo;
    }
}
