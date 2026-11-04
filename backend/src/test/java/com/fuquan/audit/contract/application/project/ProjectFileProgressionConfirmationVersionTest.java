package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFileProgressionConfirmationVersionTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CANDIDATE = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID CONTRACT = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void oldConfirmationAfterDerivedFactsVersionAdvanceOffersReconfirmationNotPromotion() {
        var firstConfirmationVersion = draft().confirmed(NOW.plusSeconds(1));
        var afterSupportReplacement = firstConfirmationVersion.confirmed(NOW.plusSeconds(2));
        var projected = project(afterSupportReplacement, confirmation());

        assertThat(projected.stage()).isEqualTo("CONTRACT_INFORMATION_INSUFFICIENT");
        assertThat(projected.availableActions()).isEmpty();
        assertThat(projected.candidateId()).isEqualTo(CANDIDATE);
    }

    @Test
    void confirmationAtCurrentCandidateVersionStillOffersPromotion() {
        var projected = project(draft().confirmed(NOW.plusSeconds(1)), confirmation());
        assertThat(projected.stage()).isEqualTo("CANDIDATE_READY");
        assertThat(projected.availableActions()).containsExactly("PROMOTE_CONTRACT");
    }

    @Test
    void promotedCandidateWithMatchingDurableAssociationOffersReview() {
        var association = new ProjectContract(PROJECT, CONTRACT, CANDIDATE, "actor", NOW);
        var projected = project(promoted(), null, Optional.of(association));

        assertThat(projected.stage()).isEqualTo("REVIEW_READY");
        assertThat(projected.blocker()).isNull();
        assertThat(projected.availableActions()).containsExactly("START_REVIEW");
        assertThat(projected.candidateId()).isEqualTo(CANDIDATE);
        assertThat(projected.contractId()).isEqualTo(association.contractId());
    }

    @Test
    void promotedCandidateWithoutDurableAssociationDoesNotOfferReview() {
        var projected = project(promoted(), null, Optional.empty());

        assertThat(projected.stage()).isNotEqualTo("REVIEW_READY");
        assertThat(projected.blocker()).isEqualTo("PROJECT_CONTRACT_ASSOCIATION_UNPROVEN");
        assertThat(projected.availableActions()).doesNotContain("START_REVIEW");
        assertThat(projected.candidateId()).isEqualTo(CANDIDATE);
        assertThat(projected.contractId()).isNull();
    }

    @Test
    void promotedCandidateWithInconsistentDurableAssociationDoesNotOfferReview() {
        var wrongAssociations = List.of(
                new ProjectContract(UUID.randomUUID(), CONTRACT, CANDIDATE, "actor", NOW),
                new ProjectContract(PROJECT, CONTRACT, UUID.randomUUID(), "actor", NOW),
                new ProjectContract(PROJECT, UUID.randomUUID(), CANDIDATE, "actor", NOW));

        for (var association : wrongAssociations) {
            var projected = project(promoted(), null, Optional.of(association));
            assertThat(projected.stage()).isNotEqualTo("REVIEW_READY");
            assertThat(projected.blocker()).isEqualTo("PROJECT_CONTRACT_ASSOCIATION_UNPROVEN");
            assertThat(projected.availableActions()).doesNotContain("START_REVIEW");
            assertThat(projected.contractId()).isNull();
        }
    }

    private static ProjectFileProgressionProjection project(ContractCandidate candidate,
            ContractCandidateConfirmation confirmation) {
        return project(candidate, confirmation, Optional.empty());
    }

    private static ProjectFileProgressionProjection project(ContractCandidate candidate,
            ContractCandidateConfirmation confirmation, Optional<ProjectContract> association) {
        var candidates = mock(ContractCandidateRepository.class);
        var confirmations = mock(ContractCandidateConfirmationRepository.class);
        var contracts = mock(ProjectContractQueryPort.class);
        when(candidates.findBySourceContractFileId(PROJECT, FILE)).thenReturn(Optional.of(candidate));
        if (confirmation != null) {
            when(confirmations.findLatestByProjectAndCandidate(PROJECT, CANDIDATE))
                    .thenReturn(Optional.of(confirmation));
        }
        when(contracts.findByCandidateId(CANDIDATE)).thenReturn(association);
        var resolver = new ProjectFileProgressionProjectionService(candidates, confirmations,
                contracts);
        var file = new ProjectFile(FILE, PROJECT, null, null, "synthetic.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, "a".repeat(64), "b".repeat(64), 1,
                "synthetic", null, NOW, NOW);
        var role = new ProjectFileRoleProjection(null, null, null, UUID.randomUUID(),
                DocumentRole.CONTRACT_BODY, null, null, null, null, null, null, null,
                null, null, null, null, null, "CONFIRMED", DocumentRole.CONTRACT_BODY,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, UUID.randomUUID(), 1L);
        var identity = new EffectiveProjectIdentity(EffectiveProjectIdentityStatus.SAME_PROJECT,
                UUID.randomUUID(), ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT,
                ProjectIdentityDecisionStatus.CONFIRMED, UUID.randomUUID(), 1L, true, false, null);
        return resolver.project(file, role, identity);
    }

    private static ContractCandidate draft() {
        return ContractCandidate.draft(CANDIDATE, PROJECT, FILE, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), NOW);
    }

    private static ContractCandidate promoted() {
        return draft().promote(CONTRACT, "actor", NOW.plusSeconds(1));
    }

    private static ContractCandidateConfirmation confirmation() {
        return new ContractCandidateConfirmation(UUID.randomUUID(), PROJECT, CANDIDATE,
                0, 1, FILE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "c".repeat(64), "{}", "synthetic-actor", NOW, null, NOW);
    }
}
