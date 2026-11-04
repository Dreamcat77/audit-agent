package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Machine assessment execution. Human decisions are stored separately. */
public record ProjectFileRoleAssessment(
        UUID assessmentId,
        UUID projectId,
        UUID fileId,
        String fileSha256,
        UUID parseResultId,
        int parseVersion,
        RoleAssessmentExecutionStatus executionStatus,
        DocumentRole proposedRole,
        BigDecimal confidence,
        boolean conflict,
        ProjectFileRoleAssessmentSource assessmentSource,
        String assessmentVersion,
        String rolePolicyVersion,
        List<ProjectFileRoleCandidateProjection> candidateRoles,
        UUID precedingDeterministicAssessmentId,
        ProjectFileRoleModelProvenance modelProvenance,
        String inputFingerprint,
        String safeReasonCode,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        int attemptNo,
        long versionNo) {

    public ProjectFileRoleAssessment {
        Objects.requireNonNull(assessmentId, "assessmentId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        fileSha256 = sha(fileSha256, "fileSha256");
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        if (parseVersion <= 0 || attemptNo < 0 || versionNo < 0) throw new IllegalArgumentException("invalid versions");
        Objects.requireNonNull(executionStatus, "executionStatus must not be null");
        Objects.requireNonNull(assessmentSource, "assessmentSource must not be null");
        assessmentVersion = required(assessmentVersion, "assessmentVersion");
        rolePolicyVersion = required(rolePolicyVersion, "rolePolicyVersion");
        candidateRoles = List.copyOf(Objects.requireNonNull(candidateRoles, "candidateRoles must not be null"));
        inputFingerprint = sha(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (assessmentSource == ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC) {
            if (modelProvenance != null || precedingDeterministicAssessmentId != null) {
                throw new IllegalArgumentException("deterministic assessment cannot have AI provenance");
            }
        } else if (modelProvenance == null || precedingDeterministicAssessmentId == null) {
            throw new IllegalArgumentException("AI assessment requires model provenance and preceding deterministic assessment");
        }
        validateState(executionStatus, proposedRole, confidence, conflict, candidateRoles,
                safeReasonCode, createdAt, startedAt, completedAt, attemptNo);
    }

    public static ProjectFileRoleAssessment pending(
            UUID assessmentId, UUID projectId, UUID fileId, String fileSha256, UUID parseResultId,
            int parseVersion, ProjectFileRoleAssessmentSource source, String assessmentVersion,
            String rolePolicyVersion, String inputFingerprint, UUID precedingDeterministicAssessmentId,
            ProjectFileRoleModelProvenance modelProvenance, Instant createdAt) {
        return new ProjectFileRoleAssessment(assessmentId, projectId, fileId, fileSha256, parseResultId,
                parseVersion, RoleAssessmentExecutionStatus.PENDING, null, null, false, source,
                assessmentVersion, rolePolicyVersion, List.of(), precedingDeterministicAssessmentId,
                modelProvenance, inputFingerprint, null, createdAt, null, null, 0, 0);
    }

    public ProjectFileRoleAssessment start(Instant at) {
        if (executionStatus != RoleAssessmentExecutionStatus.PENDING
                && executionStatus != RoleAssessmentExecutionStatus.UNKNOWN) {
            throw new IllegalStateException("assessment is not recoverable");
        }
        Objects.requireNonNull(at, "startedAt must not be null");
        return copy(RoleAssessmentExecutionStatus.RUNNING, null, null, false, List.of(), null,
                at, null, attemptNo + 1, versionNo + 1);
    }

    public ProjectFileRoleAssessment complete(DocumentRole role, BigDecimal resultConfidence, boolean resultConflict,
                                               List<ProjectFileRoleCandidateProjection> candidates,
                                               String safeReason, Instant at) {
        requireStatus(RoleAssessmentExecutionStatus.RUNNING);
        return copy(RoleAssessmentExecutionStatus.COMPLETED, role, resultConfidence, resultConflict,
                candidates, safeReason, startedAt, Objects.requireNonNull(at, "completedAt must not be null"), attemptNo, versionNo + 1);
    }

    public ProjectFileRoleAssessment finishWithoutProposal(RoleAssessmentExecutionStatus terminalStatus,
                                                            String safeReason, Instant at) {
        requireStatus(RoleAssessmentExecutionStatus.RUNNING);
        if (terminalStatus != RoleAssessmentExecutionStatus.FAILED
                && terminalStatus != RoleAssessmentExecutionStatus.UNKNOWN) {
            throw new IllegalArgumentException("terminal status must be FAILED or UNKNOWN");
        }
        return copy(terminalStatus, null, null, false, List.of(), required(safeReason, "safeReasonCode"),
                startedAt, Objects.requireNonNull(at, "completedAt must not be null"), attemptNo, versionNo + 1);
    }

    public void requireLegalSuccessor(ProjectFileRoleAssessment updated) {
        Objects.requireNonNull(updated, "updated must not be null");
        if (!sameInputIdentity(updated)) throw new IllegalArgumentException("assessment input identity cannot change");
        if (!assessmentId.equals(updated.assessmentId) || updated.versionNo != versionNo + 1) {
            throw new IllegalArgumentException("assessment CAS identity/version mismatch");
        }
        boolean legal = executionStatus == RoleAssessmentExecutionStatus.PENDING
                && updated.executionStatus == RoleAssessmentExecutionStatus.RUNNING
                || executionStatus == RoleAssessmentExecutionStatus.RUNNING
                && (updated.executionStatus == RoleAssessmentExecutionStatus.COMPLETED
                || updated.executionStatus == RoleAssessmentExecutionStatus.FAILED
                || updated.executionStatus == RoleAssessmentExecutionStatus.UNKNOWN)
                || executionStatus == RoleAssessmentExecutionStatus.UNKNOWN
                && updated.executionStatus == RoleAssessmentExecutionStatus.RUNNING;
        if (!legal) throw new IllegalStateException("illegal role assessment transition");
        int expectedAttempt = updated.executionStatus == RoleAssessmentExecutionStatus.RUNNING
                ? attemptNo + 1 : attemptNo;
        if (updated.attemptNo != expectedAttempt) throw new IllegalArgumentException("assessment attempt mismatch");
        if (!createdAt.equals(updated.createdAt)
                || executionStatus == RoleAssessmentExecutionStatus.RUNNING
                && !Objects.equals(startedAt, updated.startedAt)) {
            throw new IllegalArgumentException("assessment execution timestamps cannot drift");
        }
    }

    public boolean sameInputIdentity(ProjectFileRoleAssessment other) {
        return projectId.equals(other.projectId) && fileId.equals(other.fileId)
                && fileSha256.equals(other.fileSha256) && parseResultId.equals(other.parseResultId)
                && parseVersion == other.parseVersion && assessmentSource == other.assessmentSource
                && assessmentVersion.equals(other.assessmentVersion) && rolePolicyVersion.equals(other.rolePolicyVersion)
                && inputFingerprint.equals(other.inputFingerprint)
                && Objects.equals(precedingDeterministicAssessmentId, other.precedingDeterministicAssessmentId)
                && Objects.equals(modelProvenance, other.modelProvenance);
    }

    private ProjectFileRoleAssessment copy(RoleAssessmentExecutionStatus status, DocumentRole role,
                                           BigDecimal resultConfidence, boolean resultConflict,
                                           List<ProjectFileRoleCandidateProjection> candidates, String reason,
                                           Instant start, Instant complete, int attempt, long version) {
        return new ProjectFileRoleAssessment(assessmentId, projectId, fileId, fileSha256, parseResultId,
                parseVersion, status, role, resultConfidence, resultConflict, assessmentSource,
                assessmentVersion, rolePolicyVersion, candidates, precedingDeterministicAssessmentId,
                modelProvenance, inputFingerprint, reason, createdAt, start, complete, attempt, version);
    }

    private void requireStatus(RoleAssessmentExecutionStatus required) {
        if (executionStatus != required) throw new IllegalStateException("assessment is not " + required);
    }

    private static void validateState(RoleAssessmentExecutionStatus status, DocumentRole role,
                                      BigDecimal confidence, boolean conflict,
                                      List<ProjectFileRoleCandidateProjection> candidates, String reason,
                                      Instant created, Instant started, Instant completed, int attempt) {
        if (started != null && started.isBefore(created)) throw new IllegalArgumentException("startedAt before createdAt");
        if (completed != null && (started == null || completed.isBefore(started))) {
            throw new IllegalArgumentException("invalid completedAt");
        }
        if (status == RoleAssessmentExecutionStatus.PENDING) {
            if (attempt != 0) throw new IllegalArgumentException("PENDING attempt must be zero");
            requireEmptyResult(role, confidence, conflict, candidates, reason, started, completed);
        } else if (status == RoleAssessmentExecutionStatus.RUNNING) {
            if (attempt < 1) throw new IllegalArgumentException("RUNNING requires an attempt");
            requireEmptyResult(role, confidence, conflict, candidates, reason, null, completed);
            Objects.requireNonNull(started, "RUNNING requires startedAt");
        } else if (status == RoleAssessmentExecutionStatus.COMPLETED) {
            if (attempt < 1) throw new IllegalArgumentException("COMPLETED requires an attempt");
            Objects.requireNonNull(role, "COMPLETED requires proposedRole");
            Objects.requireNonNull(confidence, "COMPLETED requires confidence");
            Objects.requireNonNull(started, "COMPLETED requires startedAt");
            Objects.requireNonNull(completed, "COMPLETED requires completedAt");
            if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("confidence must be between zero and one");
            }
            if (candidates.isEmpty() && (role != DocumentRole.UNKNOWN || conflict
                    || confidence.compareTo(BigDecimal.ZERO) != 0)) {
                throw new IllegalArgumentException("empty candidates require an UNKNOWN empty proposal");
            }
            if (!candidates.isEmpty() && !conflict && role != candidates.get(0).role()) {
                throw new IllegalArgumentException("proposal must match leading candidate");
            }
            if (conflict && role != DocumentRole.UNKNOWN) {
                throw new IllegalArgumentException("conflict requires UNKNOWN proposal");
            }
        } else {
            if (attempt < 1) throw new IllegalArgumentException("terminal assessment requires an attempt");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("terminal failure requires safeReasonCode");
            requireEmptyResult(role, confidence, conflict, candidates, null, null, null);
            Objects.requireNonNull(started, "terminal assessment requires startedAt");
            Objects.requireNonNull(completed, "terminal assessment requires completedAt");
        }
    }

    private static void requireEmptyResult(DocumentRole role, BigDecimal confidence, boolean conflict,
                                           List<ProjectFileRoleCandidateProjection> candidates, String reason,
                                           Instant forbiddenStarted, Instant completed) {
        if (role != null || confidence != null || conflict || !candidates.isEmpty() || reason != null
                || forbiddenStarted != null || completed != null) {
            throw new IllegalArgumentException("non-completed assessment cannot carry a proposal");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String sha(String value, String name) {
        value = required(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        return value;
    }
}
