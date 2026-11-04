package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFileIdentityDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String SHA = "a".repeat(64);

    @Test
    void deterministicAssessmentCannotClaimAutomaticSameProject() {
        assertThat(ProjectIdentityAssessmentStatus.values()).extracting(Enum::name)
                .containsExactly("LIKELY_SAME_PROJECT", "CONFLICT", "INSUFFICIENT", "SAME_PROJECT");
        assertThatThrownBy(() -> assessment(ProjectIdentityAssessmentStatus.SAME_PROJECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("automatic SAME_PROJECT");
        assertThat(assessment(ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT).assessmentSource())
                .isEqualTo(ProjectIdentityAssessmentSource.DETERMINISTIC);
    }

    @Test
    void humanDecisionRequiresExactBoundDurableIdentityAndMonotonicVersion() {
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID parseId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        ProjectFileIdentityDecision decision = new ProjectFileIdentityDecision(UUID.randomUUID(), projectId, 1,
                fileId, SHA, parseId, 1, assessmentId,
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, UUID.randomUUID().toString(), NOW,
                null, "verified source", 1);
        assertThat(decision.resolution()).isEqualTo(ProjectIdentityResolution.CONFIRM_SAME_PROJECT);
        assertThatThrownBy(() -> new ProjectFileIdentityDecision(UUID.randomUUID(), projectId, 0,
                fileId, SHA, parseId, 1, assessmentId,
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, UUID.randomUUID().toString(), NOW,
                null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectFileIdentityDecision(UUID.randomUUID(), projectId, 1,
                fileId, SHA, parseId, 1, assessmentId,
                ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, UUID.randomUUID().toString(), NOW,
                null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceRejectsUnboundedValueAndLocator() {
        assertThatThrownBy(() -> new ProjectFileIdentityEvidence(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, UUID.randomUUID(), SHA, UUID.randomUUID(), 1,
                "PROJECT_NO", UUID.randomUUID(), "PROJECT_METADATA", "PARSE_FACT", SHA, SHA,
                "x".repeat(161), "P-2026-001", ProjectIdentityComparisonOutcome.MATCH,
                "STRONG", "paragraph:1", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectFileIdentityAssessment assessment(ProjectIdentityAssessmentStatus status) {
        return new ProjectFileIdentityAssessment(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), SHA, UUID.randomUUID(), 1, ProjectIdentityAssessmentSource.DETERMINISTIC,
                "identity-assessment-v1", "human-same-only-v1", status, SHA, null,
                NOW, NOW, 0);
    }
}
