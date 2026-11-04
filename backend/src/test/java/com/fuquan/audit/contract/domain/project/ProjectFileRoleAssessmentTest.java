package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFileRoleAssessmentTest {
    private static final Instant CREATED = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void keepsExecutionAndBusinessDecisionStatusesSeparate() {
        assertThat(RoleAssessmentExecutionStatus.values()).extracting(Enum::name)
                .containsExactly("PENDING", "RUNNING", "COMPLETED", "FAILED", "UNKNOWN");
        assertThat(RoleDecisionStatus.values()).extracting(Enum::name)
                .containsExactly("PROPOSED", "NEEDS_CONFIRMATION", "CONFIRMED");
    }

    @Test
    void permitsOnlyPendingToRunningAndRunningToTerminalCasTransitions() {
        ProjectFileRoleAssessment pending = pending();
        ProjectFileRoleAssessment running = pending.start(CREATED.plusSeconds(1));
        ProjectFileRoleAssessment completed = running.complete(
                DocumentRole.CONTRACT_BODY, new BigDecimal("0.9200"), false,
                List.of(new ProjectFileRoleCandidateProjection(DocumentRole.CONTRACT_BODY,
                        new BigDecimal("8.50"), new BigDecimal("0.9200"), "CONTRACT_STRUCTURE")),
                null, CREATED.plusSeconds(2));

        assertThat(running.executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.RUNNING);
        assertThat(running.versionNo()).isEqualTo(1);
        assertThat(completed.executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.COMPLETED);
        assertThat(completed.versionNo()).isEqualTo(2);
        assertThatThrownBy(() -> pending.complete(DocumentRole.CONTRACT_BODY, BigDecimal.ONE, false,
                List.of(new ProjectFileRoleCandidateProjection(DocumentRole.CONTRACT_BODY,
                        BigDecimal.ONE, BigDecimal.ONE, "DIRECT")), null, CREATED.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completed.start(CREATED.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legalSuccessorRejectsCreatedAtDriftEvenWhenAllBusinessInputsMatch() {
        ProjectFileRoleAssessment pending = pending();
        ProjectFileRoleAssessment drifted = ProjectFileRoleAssessment.pending(pending.assessmentId(), pending.projectId(),
                pending.fileId(), pending.fileSha256(), pending.parseResultId(), pending.parseVersion(),
                pending.assessmentSource(), pending.assessmentVersion(), pending.rolePolicyVersion(),
                pending.inputFingerprint(), pending.precedingDeterministicAssessmentId(), pending.modelProvenance(),
                pending.createdAt().plusSeconds(1)).start(pending.createdAt().plusSeconds(2));

        assertThatThrownBy(() -> pending.requireLegalSuccessor(drifted))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timestamps");
    }

    @Test
    void terminalFailureAndUnknownRequireSafeReasonAndCannotCarryProposal() {
        ProjectFileRoleAssessment running = pending().start(CREATED.plusSeconds(1));

        assertThat(running.finishWithoutProposal(RoleAssessmentExecutionStatus.FAILED,
                "DETERMINISTIC_VALIDATION_FAILED", CREATED.plusSeconds(2)).safeReasonCode())
                .isEqualTo("DETERMINISTIC_VALIDATION_FAILED");
        assertThat(running.finishWithoutProposal(RoleAssessmentExecutionStatus.UNKNOWN,
                "OUTCOME_UNPROVEN", CREATED.plusSeconds(2)).executionStatus())
                .isEqualTo(RoleAssessmentExecutionStatus.UNKNOWN);
        assertThatThrownBy(() -> running.finishWithoutProposal(RoleAssessmentExecutionStatus.COMPLETED,
                "WRONG", CREATED.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRecoveryReusesIdentityAndIncrementsAttemptWhileResettingTerminalOutcome() {
        ProjectFileRoleAssessment unknown = pending().start(CREATED.plusSeconds(1))
                .finishWithoutProposal(RoleAssessmentExecutionStatus.UNKNOWN, "OUTCOME_UNPROVEN", CREATED.plusSeconds(2));
        ProjectFileRoleAssessment recovered = unknown.start(CREATED.plusSeconds(3));

        assertThat(unknown.attemptNo()).isEqualTo(1);
        assertThat(recovered.assessmentId()).isEqualTo(unknown.assessmentId());
        assertThat(recovered.executionStatus()).isEqualTo(RoleAssessmentExecutionStatus.RUNNING);
        assertThat(recovered.attemptNo()).isEqualTo(2);
        assertThat(recovered.safeReasonCode()).isNull();
        assertThat(recovered.completedAt()).isNull();
        assertThat(recovered.startedAt()).isEqualTo(CREATED.plusSeconds(3));
    }

    @Test
    void deterministicAssessmentRejectsModelProvenanceAndAiAssessmentRequiresIt() {
        assertThatThrownBy(() -> copyPending(ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                new ProjectFileRoleModelProvenance("provider", "model", "rev", "prompt-v1", "schema-v1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyPending(ProjectFileRoleAssessmentSource.MACHINE_AI, null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(copyPending(ProjectFileRoleAssessmentSource.MACHINE_AI,
                new ProjectFileRoleModelProvenance("provider", "model", "rev", "prompt-v1", "schema-v1"),
                UUID.randomUUID()).assessmentSource()).isEqualTo(ProjectFileRoleAssessmentSource.MACHINE_AI);
    }

    private static ProjectFileRoleAssessment pending() {
        return copyPending(ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC, null, null);
    }

    private static ProjectFileRoleAssessment copyPending(ProjectFileRoleAssessmentSource source,
                                                           ProjectFileRoleModelProvenance provenance,
                                                           UUID preceding) {
        return ProjectFileRoleAssessment.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha("file"),
                UUID.randomUUID(), 1, source, "role-assessment-v1", "manual-confirmation-v1",
                sha("input-fingerprint"), preceding, provenance, CREATED);
    }

    static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
