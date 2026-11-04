package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectiveProjectIdentityResolverTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private final UUID projectId = UUID.randomUUID(), fileId = UUID.randomUUID(), parseId = UUID.randomUUID();
    private final String text = "项目编号 P-2026-001";
    private final ProjectFile file = new ProjectFile(fileId, projectId, null, null, "材料2.pdf",
            "application/pdf", ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
            ProjectFileParseStatus.PARSED, sha("file"), sha("file"), 100, text, null, NOW, NOW);
    private final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
    private final InMemoryProjectFileIdentityAssessmentRepository assessments = new InMemoryProjectFileIdentityAssessmentRepository();
    private final InMemoryProjectFileIdentityDecisionRepository decisions = new InMemoryProjectFileIdentityDecisionRepository();
    private final EffectiveProjectIdentityResolver resolver = new EffectiveProjectIdentityResolver(
            parses, assessments, decisions);

    @Test
    void assessmentProposalAloneCannotBecomeSameProjectAuthority() {
        seedParseAndAssessment();
        var projected = resolver.resolve(project(1), file);
        assertThat(projected.status()).isEqualTo(EffectiveProjectIdentityStatus.LIKELY_SAME_PROJECT);
        assertThat(projected.currentHumanSameDecision()).isNull();
        assertThat(projected.excludedFromProjectUse()).isFalse();
    }

    @Test
    void currentHumanSameAndNotSameControlEligibilityWithoutChangingFileStatus() {
        var a = seedParseAndAssessment();
        var same = decision(a, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 1);
        decisions.insertIfAbsent(same);
        assertThat(resolver.resolve(project(1), file).status())
                .isEqualTo(EffectiveProjectIdentityStatus.SAME_PROJECT);
        assertThat(resolver.resolve(project(1), file).currentHumanSameDecision()).isEqualTo(same);

        var negative = decision(a, ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, 2);
        decisions.insertIfAbsent(negative);
        var projected = resolver.resolve(project(1), file);
        assertThat(projected.status()).isEqualTo(EffectiveProjectIdentityStatus.NOT_SAME_PROJECT);
        assertThat(projected.excludedFromProjectUse()).isTrue();
        assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
    }

    @Test
    void projectVersionChangeMakesOldDecisionHistoricalButNotCurrent() {
        var a = seedParseAndAssessment();
        decisions.insertIfAbsent(decision(a, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 1));
        var projected = resolver.resolve(project(2), file);
        assertThat(projected.status()).isEqualTo(EffectiveProjectIdentityStatus.INSUFFICIENT);
        assertThat(projected.identityCurrent()).isFalse();
        assertThat(projected.currentHumanSameDecision()).isNull();
        assertThat(projected.decisionId()).isNotNull();
        assertThat(projected.recoveryReason())
                .isEqualTo(EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE);
    }

    @Test
    void assessmentOnlyRemainsRediscoverableAfterProjectVersionChangesWithoutGrantingAuthority() {
        var assessment = seedParseAndAssessment();
        var projected = resolver.resolve(project(2), file);
        assertThat(projected.status()).isEqualTo(EffectiveProjectIdentityStatus.INSUFFICIENT);
        assertThat(projected.assessmentId()).isEqualTo(assessment.assessmentId());
        assertThat(projected.assessmentStatus()).isEqualTo(assessment.status());
        assertThat(projected.identityCurrent()).isFalse();
        assertThat(projected.currentHumanSameDecision()).isNull();
        assertThat(projected.recoveryReason())
                .isEqualTo(EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE);
    }

    @Test
    void missingAssessmentOnConfirmedProjectIsRecoverableWithoutGrantingAuthority() {
        parses.insertIfAbsent(new ProjectFileParseResult(parseId, projectId, fileId, file.sha256(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "pdfbox", "v1", text, sha(text), 1, 1L,
                null, null, NOW, NOW, 0));

        var projected = resolver.resolve(project(1), file);

        assertThat(projected.status()).isEqualTo(EffectiveProjectIdentityStatus.INSUFFICIENT);
        assertThat(projected.recoveryReason())
                .isEqualTo(EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_PENDING);
        assertThat(projected.identityCurrent()).isFalse();
    }

    @Test
    void unconfirmedProjectRequiresMetadataInsteadOfMachineRecovery() {
        var projected = resolver.resolve(project(0), file);

        assertThat(projected.recoveryReason())
                .isEqualTo(EffectiveProjectIdentityRecoveryReason.PROJECT_METADATA_CONFIRMATION_REQUIRED);
    }

    private ProjectFileIdentityAssessment seedParseAndAssessment() {
        parses.insertIfAbsent(new ProjectFileParseResult(parseId, projectId, fileId, file.sha256(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "pdfbox", "v1", text, sha(text), 1, 1L,
                null, null, NOW, NOW, 0));
        var a = new ProjectFileIdentityAssessment(UUID.randomUUID(), projectId, 1, fileId, file.sha256(),
                parseId, 1, ProjectIdentityAssessmentSource.DETERMINISTIC,
                "identity-assessment-v1", "human-same-only-v1",
                ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT, sha("input"), null, NOW, NOW, 0);
        assessments.insertIfAbsent(a);
        return a;
    }
    private ProjectFileIdentityDecision decision(ProjectFileIdentityAssessment a,
            ProjectIdentityResolution resolution, long version) {
        return new ProjectFileIdentityDecision(UUID.randomUUID(), projectId, a.projectVersionNo(), fileId,
                file.sha256(), parseId, 1, a.assessmentId(), resolution, UUID.randomUUID().toString(), NOW,
                null, null, version);
    }
    private Project project(long version) {
        return new Project(projectId, "合成项目", "P-2026-001", null, "actor", version, NOW, NOW);
    }
    private static String sha(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
