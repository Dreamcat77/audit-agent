package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8ProjectFileIdentityRepositoriesTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String SHA = "a".repeat(64);
    @Mock private ProjectFileIdentityAssessmentMapper assessments;
    @Mock private ProjectFileIdentityDecisionMapper decisions;

    @Test
    void assessmentDuplicateIdentityReturnsOnlyExactDurableWinner() {
        var value = assessment();
        when(assessments.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(assessments.findById(value.assessmentId().toString())).thenReturn(
                Dm8ProjectFileIdentityAssessmentRepository.row(value));
        var repo = new Dm8ProjectFileIdentityAssessmentRepository(assessments);
        assertThat(repo.insertIfAbsent(value)).isEqualTo(value);
        var collision = new ProjectFileIdentityAssessment(value.assessmentId(), value.projectId(), 2,
                value.fileId(), SHA, value.parseResultId(), 1,
                ProjectIdentityAssessmentSource.DETERMINISTIC, value.assessmentVersion(),
                value.identityPolicyVersion(), value.status(), SHA, null, NOW, NOW, 0);
        assertThatThrownBy(() -> repo.insertIfAbsent(collision)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decisionDuplicateStreamRaceFailsClosedAndConnectivityIsNotMissing() {
        var a = assessment();
        var decision = new ProjectFileIdentityDecision(UUID.randomUUID(), a.projectId(), 1, a.fileId(),
                SHA, a.parseResultId(), 1, a.assessmentId(), ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
                UUID.randomUUID().toString(), NOW, null, null, 1);
        when(decisions.insert(any())).thenThrow(new DuplicateKeyException("version race"));
        when(decisions.findByFileIdAndVersion(a.fileId().toString(), 1)).thenReturn(
                Dm8ProjectFileIdentityDecisionRepository.row(new ProjectFileIdentityDecision(
                        UUID.randomUUID(), a.projectId(), 1, a.fileId(), SHA, a.parseResultId(), 1,
                        a.assessmentId(), ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT,
                        UUID.randomUUID().toString(), NOW, null, null, 1)));
        assertThatThrownBy(() -> new Dm8ProjectFileIdentityDecisionRepository(decisions)
                .insertIfAbsent(decision)).isInstanceOf(IllegalStateException.class);

        when(decisions.findById(decision.decisionId().toString()))
                .thenThrow(new DataAccessResourceFailureException("dm8 unavailable"));
        assertThatThrownBy(() -> new Dm8ProjectFileIdentityDecisionRepository(decisions)
                .findById(decision.decisionId())).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static ProjectFileIdentityAssessment assessment() {
        return new ProjectFileIdentityAssessment(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), SHA, UUID.randomUUID(), 1, ProjectIdentityAssessmentSource.DETERMINISTIC,
                "identity-assessment-v1", "human-same-only-v1",
                ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT, SHA, null, NOW, NOW, 0);
    }
}
