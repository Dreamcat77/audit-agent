package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityRecoveryReason;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectFileProgressionRecoveryProjectionTest {
    private final ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
    private final ContractCandidateConfirmationRepository confirmations =
            mock(ContractCandidateConfirmationRepository.class);
    private final ProjectContractQueryPort contracts = mock(ProjectContractQueryPort.class);
    private final ProjectFileRecoveryInputFingerprint fingerprints =
            mock(ProjectFileRecoveryInputFingerprint.class);
    private final ContractFactExtractionRepository factExtractions =
            mock(ContractFactExtractionRepository.class);
    private final ContractFactCandidatePort factCandidates = mock(ContractFactCandidatePort.class);
    private static final Provenance PROVENANCE = new Provenance("provider", "model", "revision",
            "prompt", "schema");
    private ProjectFileProgressionProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ProjectFileProgressionProjectionService(candidates, confirmations, contracts,
                fingerprints, factExtractions, factCandidates);
        when(factCandidates.configuredProvenance()).thenReturn(PROVENANCE);
        when(fingerprints.createIfCurrentParseAvailable(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of("sha256:recovery-input"));
        when(candidates.findBySourceContractFileId(file().projectId(), file().id()))
                .thenReturn(Optional.empty());
    }

    @Test
    void parsedFileWithoutRoleAssessmentOffersSingleFileRoleResume() {
        var result = service.project(file(), role(null, null), identity(
                EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_PENDING));

        assertThat(result.blocker()).isEqualTo("ROLE_ASSESSMENT_PENDING");
        assertThat(result.availableActions()).containsExactly("RESUME_ROLE_ASSESSMENT");
        assertThat(result.recoveryInputFingerprint()).isEqualTo("sha256:recovery-input");
    }

    @Test
    void staleIdentityOffersIdentityResumeAndBindsFingerprint() {
        var result = service.project(file(), role(UUID.randomUUID(), "CONFIRMED"), identity(
                EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE));

        assertThat(result.blocker()).isEqualTo("IDENTITY_ASSESSMENT_STALE");
        assertThat(result.availableActions()).containsExactly("RESUME_IDENTITY_ASSESSMENT");
        assertThat(result.recoveryInputFingerprint()).isEqualTo("sha256:recovery-input");
    }

    @Test
    void unconfirmedProjectMetadataDoesNotOfferMachineResume() {
        var result = service.project(file(), role(UUID.randomUUID(), "CONFIRMED"), identity(
                EffectiveProjectIdentityRecoveryReason.PROJECT_METADATA_CONFIRMATION_REQUIRED));

        assertThat(result.blocker()).isEqualTo("PROJECT_METADATA_CONFIRMATION_REQUIRED");
        assertThat(result.availableActions()).isEmpty();
        assertThat(result.recoveryInputFingerprint()).isNull();
    }

    @Test
    void currentSameProjectRequiresFactExtractionBeforeMaterialization() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, UUID.randomUUID(), null,
                ProjectIdentityDecisionStatus.CONFIRMED, UUID.randomUUID(), 1L,
                true, false, null, null);

        var result = service.project(file(), role(UUID.randomUUID(), "CONFIRMED"), identity);

        assertThat(result.blocker()).isEqualTo("FACT_EXTRACTION_PENDING");
        assertThat(result.availableActions()).containsExactly("RESUME_FACT_EXTRACTION");
        assertThat(result.recoveryInputFingerprint()).isEqualTo("sha256:recovery-input");
    }

    @Test
    void succeededFactExtractionAllowsCandidateMaterializationResume() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, UUID.randomUUID(), null,
                ProjectIdentityDecisionStatus.CONFIRMED, UUID.randomUUID(), 1L,
                true, false, null, null);
        ProjectFileRoleProjection role = role(UUID.randomUUID(), "CONFIRMED");
        ContractFactExtractionAttempt succeeded = mockSucceededAttempt();
        when(factExtractions.findLatestForAuthority(file().projectId(), file().id(),
                role.parseResultId(), role.roleDecisionId(), identity.decisionId(),
                role.effectiveRole(), PROVENANCE)).thenReturn(Optional.of(succeeded));

        var result = service.project(file(), role, identity);

        assertThat(result.blocker()).isEqualTo("CANDIDATE_MATERIALIZATION_PENDING");
        assertThat(result.availableActions()).containsExactly("RESUME_CANDIDATE_MATERIALIZATION");
        assertThat(result.recoveryInputFingerprint()).isEqualTo("sha256:recovery-input");
    }

    @Test
    void terminalFailedOrUnknownExtractionProducesAnInsufficientResultInsteadOfPendingForever() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, UUID.randomUUID(), null,
                ProjectIdentityDecisionStatus.CONFIRMED, UUID.randomUUID(), 1L,
                true, false, null, null);
        ProjectFileRoleProjection role = role(UUID.randomUUID(), "CONFIRMED");
        for (var status : List.of(ContractFactExtractionAttempt.Status.FAILED,
                ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN)) {
            var terminal = mock(ContractFactExtractionAttempt.class);
            when(terminal.status()).thenReturn(status);
            when(terminal.acceptedCount()).thenReturn(0);
            when(factExtractions.findLatestForAuthority(file().projectId(), file().id(),
                    role.parseResultId(), role.roleDecisionId(), identity.decisionId(),
                    role.effectiveRole(), PROVENANCE)).thenReturn(Optional.of(terminal));

            var result = service.project(file(), role, identity);

            assertThat(result.stage()).isEqualTo("CONTRACT_INFORMATION_INSUFFICIENT");
            assertThat(result.blocker()).isEqualTo("FACTS_NOT_VERIFIED");
            assertThat(result.availableActions()).isEmpty();
            assertThat(result.recoveryInputFingerprint()).isNull();
        }
    }

    @Test
    void successfulExtractionWithNoGroundedFactsIsACompletedInsufficientResult() {
        EffectiveProjectIdentity identity = new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, UUID.randomUUID(), null,
                ProjectIdentityDecisionStatus.CONFIRMED, UUID.randomUUID(), 1L,
                true, false, null, null);
        ProjectFileRoleProjection role = role(UUID.randomUUID(), "CONFIRMED");
        var completed = mock(ContractFactExtractionAttempt.class);
        when(completed.status()).thenReturn(ContractFactExtractionAttempt.Status.SUCCEEDED);
        when(completed.acceptedCount()).thenReturn(0);
        when(factExtractions.findLatestForAuthority(file().projectId(), file().id(),
                role.parseResultId(), role.roleDecisionId(), identity.decisionId(),
                role.effectiveRole(), PROVENANCE)).thenReturn(Optional.of(completed));

        var result = service.project(file(), role, identity);

        assertThat(result.stage()).isEqualTo("CONTRACT_INFORMATION_INSUFFICIENT");
        assertThat(result.blocker()).isEqualTo("FACTS_NOT_VERIFIED");
        assertThat(result.availableActions()).isEmpty();
    }

    private static ProjectFile file() {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        return new ProjectFile(UUID.fromString("32000000-0000-0000-0000-000000000002"),
                UUID.fromString("32000000-0000-0000-0000-000000000001"), null, null,
                "contract.docx", "application/docx", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                "b".repeat(64), "a".repeat(64), 8, "contract", null, now, now);
    }

    private static ProjectFileRoleProjection role(UUID decisionId, String decisionStatus) {
        return new ProjectFileRoleProjection(UUID.randomUUID(), 1, "CURRENT_PIPELINE",
                decisionId == null ? null : UUID.randomUUID(), DocumentRole.CONTRACT_BODY,
                null, decisionId == null ? null : "COMPLETED", null, null, null, null,
                null, null, null, null, null, null, decisionStatus, DocumentRole.CONTRACT_BODY,
                decisionId == null ? EffectiveDocumentRoleSource.NO_AUTHORITATIVE_ROLE
                        : EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                decisionId, decisionId == null ? null : 1L);
    }

    private static EffectiveProjectIdentity identity(EffectiveProjectIdentityRecoveryReason reason) {
        return new EffectiveProjectIdentity(EffectiveProjectIdentityStatus.INSUFFICIENT,
                null, null, null, null, null, false, false, null, reason);
    }

    private static ContractFactExtractionAttempt mockSucceededAttempt() {
        ContractFactExtractionAttempt attempt = mock(ContractFactExtractionAttempt.class);
        when(attempt.status()).thenReturn(ContractFactExtractionAttempt.Status.SUCCEEDED);
        when(attempt.acceptedCount()).thenReturn(3);
        return attempt;
    }
}
