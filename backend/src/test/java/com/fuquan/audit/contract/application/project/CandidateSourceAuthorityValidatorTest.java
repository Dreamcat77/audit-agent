package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileRoleDecisionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CandidateSourceAuthorityValidatorTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"role", "identity", "parse", "missing", "fingerprint", "cohort"})
    void fullSnapshotRejectsInvalidSupportWhilePrimarySnapshotStillPermitsRepair(String drift) {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        f.drift(drift);
        assertThatThrownBy(() -> f.supportAuthority.validateCurrent(f.current()))
                .isInstanceOf(BusinessException.class);

        assertThat(f.sourceAuthority.snapshot(f.current()).promotable()).isTrue();
        assertThatThrownBy(() -> f.sourceAuthority.snapshotWithCurrentSupport(f.current()).requirePromotable())
                .as("shared snapshot must reject invalid assigned support: " + drift)
                .isInstanceOf(BusinessException.class);
        f.replace(List.of());
        assertThat(f.sourceAuthority.snapshotWithCurrentSupport(f.current()).promotable()).isTrue();
    }

    @Test
    void prePhase1dDraftCandidateCannotPromoteWithoutCurrentHumanSameAndReauthorization() {
        Support support = new Support(ProjectFileCategory.CONTRACT_BODY);
        support.prepareLegacyParse();

        CandidateSourceAuthority authority = support.validator.snapshot(support.candidate);

        assertThat(authority.promotable()).isFalse();
        assertThat(authority.effectiveRoleSource()).isEqualTo(EffectiveDocumentRoleSource.LEGACY_EFFECTIVE);
        assertThat(authority.currentRoleDecisionId()).isNull();
        assertThatThrownBy(authority::requirePromotable)
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.parses.findLatestByFileId(FILE).orElseThrow().parseOrigin())
                .isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
    }

    @Test
    void legacyUnknownCandidateFailsClosedAfterPreparingItsHistoricalParse() {
        Support support = new Support(ProjectFileCategory.UNKNOWN);
        support.prepareLegacyParse();

        CandidateSourceAuthority authority = support.validator.snapshot(support.candidate);

        assertThat(authority.promotable()).isFalse();
        assertThatThrownBy(authority::requirePromotable)
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.parses.findLatestByFileId(FILE).orElseThrow().parseOrigin())
                .isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
    }

    @Test
    void validatorCannotMaterializeLegacyParseInsidePromotionWriteTransaction() {
        Support support = new Support(ProjectFileCategory.CONTRACT_BODY);

        assertThatThrownBy(() -> support.validator.snapshot(support.candidate))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(support.parses.findLatestByFileId(FILE)).isEmpty();
    }

    @Test
    void exactCurrentHumanRoleAndProjectIdentityPairPromotesButSupersededIdentityDoesNot() {
        Support support = new Support(ProjectFileCategory.UNKNOWN);
        support.prepareLegacyParse();
        ContractCandidate authorized = support.authorizeDraft();
        assertThat(support.validator.snapshot(authorized).promotable()).isTrue();

        var old = support.identityDecisions.findLatestByFileId(FILE).orElseThrow();
        support.identityDecisions.insertIfAbsent(new ProjectFileIdentityDecision(UUID.randomUUID(), PROJECT,
                support.project.get().versionNo(), FILE, support.file.sha256(), old.parseResultId(),
                old.parseVersion(), old.assessmentId(), ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT,
                "synthetic-user", NOW.plusSeconds(1), old.decisionId(), "wrong project", 2));
        var changed = support.validator.snapshot(authorized);
        assertThat(changed.promotable()).isFalse();
        assertThat(changed.rejectionReason()).contains("project identity");
    }

    @Test
    void projectMetadataVersionChangeInvalidatesHistoricalSameWithoutChangingDecisionRow() {
        Support support = new Support(ProjectFileCategory.UNKNOWN);
        support.prepareLegacyParse();
        ContractCandidate authorized = support.authorizeDraft();
        assertThat(support.validator.snapshot(authorized).promotable()).isTrue();
        support.project.updateAndGet(current -> current.confirmMetadata(1,
                "Changed synthetic Project", "SYN-2026-002", null, NOW.plusSeconds(2)));
        assertThat(support.validator.snapshot(authorized).promotable()).isFalse();
    }

    private static final class Support {
        final ProjectFile file;
        final ContractCandidate candidate;
        final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
        final InMemoryProjectFileRoleDecisionRepository decisions =
                new InMemoryProjectFileRoleDecisionRepository();
        final InMemoryProjectFileIdentityAssessmentRepository identityAssessments =
                new InMemoryProjectFileIdentityAssessmentRepository();
        final InMemoryProjectFileIdentityDecisionRepository identityDecisions =
                new InMemoryProjectFileIdentityDecisionRepository();
        final AtomicReference<Project> project = new AtomicReference<>(Project.blank(PROJECT,
                "synthetic-user", NOW).confirmMetadata(0, "Synthetic Project", "SYN-2026-001", null, NOW));
        final CandidateSourceAuthorityValidator validator;
        final LegacyProjectFileParseResultMaterializer legacy;

        Support(ProjectFileCategory category) {
            file = new ProjectFile(FILE, PROJECT, null, "001.pdf", "001.pdf", "application/pdf", category,
                    ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, "a".repeat(64),
                    "b".repeat(64), 100, "synthetic persisted contract body", null, NOW, NOW,
                    new ProjectFileParseProvenance(ProjectFileParseMethod.PDF_TEXT, "PDFBOX_TEST", null,
                            1, 2L));
            candidate = ContractCandidate.draft(UUID.randomUUID(), PROJECT, FILE, "LEGACY-1",
                    "Synthetic Contract", "GOODS", "Buyer", "Supplier", null,
                    new BigDecimal("10.00"), null, null, null, null, List.of(), NOW);
            ProjectFileRepository files = org.mockito.Mockito.mock(ProjectFileRepository.class);
            org.mockito.Mockito.when(files.findByProjectIdAndId(PROJECT, FILE)).thenReturn(Optional.of(file));
            legacy = new LegacyProjectFileParseResultMaterializer(files, parses);
            EffectiveDocumentRoleResolver roles = new EffectiveDocumentRoleResolver(parses, decisions);
            ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
            org.mockito.Mockito.when(projects.findById(PROJECT)).thenAnswer(ignored -> Optional.of(project.get()));
            validator = new CandidateSourceAuthorityValidator(files, parses, decisions, roles,
                    projects, identityDecisions,
                    new EffectiveProjectIdentityResolver(parses, identityAssessments, identityDecisions),
                    org.mockito.Mockito.mock(CandidateSupportAuthorityValidator.class));
        }

        void prepareLegacyParse() { legacy.materialize(PROJECT, FILE); }

        ContractCandidate authorizeDraft() {
            var parse = parses.findLatestByFileId(FILE).orElseThrow();
            var role = new ProjectFileRoleDecision(UUID.randomUUID(), PROJECT, FILE, file.sha256(),
                    parse.parseResultId(), parse.parseVersion(), UUID.randomUUID(), DocumentRole.CONTRACT_BODY,
                    RoleDecisionStatus.CONFIRMED, "synthetic-user", NOW, null, "synthetic role", 1);
            decisions.insertIfAbsent(role);
            UUID assessmentId = UUID.randomUUID();
            identityAssessments.insertIfAbsent(new ProjectFileIdentityAssessment(assessmentId, PROJECT,
                    project.get().versionNo(), FILE, file.sha256(), parse.parseResultId(), parse.parseVersion(),
                    ProjectIdentityAssessmentSource.DETERMINISTIC, "identity-v1", "human-only-v1",
                    ProjectIdentityAssessmentStatus.INSUFFICIENT, "c".repeat(64), null, NOW, NOW, 0));
            var identity = new ProjectFileIdentityDecision(UUID.randomUUID(), PROJECT,
                    project.get().versionNo(), FILE, file.sha256(), parse.parseResultId(), parse.parseVersion(),
                    assessmentId, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, "synthetic-user",
                    NOW, null, "synthetic project ownership", 1);
            identityDecisions.insertIfAbsent(identity);
            return candidate.reauthorize(parse.parseResultId(), role.decisionId(), identity.decisionId(),
                    "d".repeat(64), NOW);
        }
    }
}
