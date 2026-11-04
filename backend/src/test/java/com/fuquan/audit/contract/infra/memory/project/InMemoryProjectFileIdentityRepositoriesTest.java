package com.fuquan.audit.contract.infra.memory.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityComparisonOutcome;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryProjectFileIdentityRepositoriesTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String SHA = "a".repeat(64);

    @Test
    void stagedAssessmentAndEvidenceRemainInvisibleUntilCommittedAndRollbackExactAttempt() {
        var assessments = new InMemoryProjectFileIdentityAssessmentRepository();
        var evidence = new InMemoryProjectFileIdentityEvidenceRepository();
        UUID project = UUID.randomUUID(), file = UUID.randomUUID(), parse = UUID.randomUUID();
        var a = assessment(project, file, parse);
        var e = new ProjectFileIdentityEvidence(UUID.randomUUID(), a.assessmentId(), project, 1, file,
                SHA, parse, 1, "PROJECT_NO", UUID.randomUUID(), "PROJECT_METADATA", "PARSE_FACT",
                SHA, SHA, "P-2026-001", "P-2026-001", ProjectIdentityComparisonOutcome.MATCH,
                "STRONG", "paragraph:1", NOW);
        assessments.stageInsertIfAbsent(a);
        evidence.stageInsertAll(a.assessmentId(), List.of(e));
        assertThat(assessments.findById(a.assessmentId())).isEmpty();
        assertThat(evidence.findByAssessmentId(a.assessmentId())).isEmpty();
        evidence.commitStagedInsert(a.assessmentId());
        assessments.commitStagedInsert(a);
        assertThat(assessments.findById(a.assessmentId())).contains(a);
        assertThat(evidence.findByAssessmentId(a.assessmentId())).containsExactly(e);

        var failed = assessment(project, file, parse);
        assessments.stageInsertIfAbsent(failed);
        assessments.rollbackUncommittedInsert(failed);
        assertThat(assessments.findById(failed.assessmentId())).isEmpty();
    }

    @Test
    void decisionStreamIsAppendOnlyAndOneWinnerPerFileVersion() {
        var repo = new InMemoryProjectFileIdentityDecisionRepository();
        UUID project = UUID.randomUUID(), file = UUID.randomUUID(), parse = UUID.randomUUID();
        var a = assessment(project, file, parse);
        var same = decision(a, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 1);
        repo.stageInsertIfAbsent(same);
        assertThat(repo.findLatestByFileId(file)).isEmpty();
        var conflicting = decision(a, ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 1);
        assertThatThrownBy(() -> repo.stageInsertIfAbsent(conflicting)).isInstanceOf(IllegalStateException.class);
        repo.commitStagedInsert(same);
        assertThat(repo.findLatestByFileId(file)).contains(same);
        var later = decision(a, ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 2);
        repo.stageInsertIfAbsent(later);
        repo.rollbackUncommittedInsert(later);
        assertThat(repo.findLatestByFileId(file)).contains(same);
    }

    private static ProjectFileIdentityAssessment assessment(UUID project, UUID file, UUID parse) {
        return new ProjectFileIdentityAssessment(UUID.randomUUID(), project, 1, file, SHA, parse, 1,
                ProjectIdentityAssessmentSource.DETERMINISTIC, "identity-assessment-v1", "human-same-only-v1",
                ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT, SHA, null, NOW, NOW, 0);
    }
    private static ProjectFileIdentityDecision decision(ProjectFileIdentityAssessment a,
            ProjectIdentityResolution resolution, int version) {
        return new ProjectFileIdentityDecision(UUID.randomUUID(), a.projectId(), a.projectVersionNo(),
                a.fileId(), a.fileSha256(), a.parseResultId(), a.parseVersion(), a.assessmentId(),
                resolution, UUID.randomUUID().toString(), NOW, null, null, version);
    }
}
