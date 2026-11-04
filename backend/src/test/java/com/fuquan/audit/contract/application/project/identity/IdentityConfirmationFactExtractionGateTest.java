package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationService;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResult;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityConfirmationFactExtractionGateTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final String SHA = "a".repeat(64);
    private final UUID projectId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID decisionId = UUID.randomUUID();
    private final UUID parseId = UUID.randomUUID();
    private final UUID roleDecisionId = UUID.randomUUID();
    private final ProjectFileRepository files = mock(ProjectFileRepository.class);
    private final IdentityConfirmationTransactionCoordinator transactions =
            mock(IdentityConfirmationTransactionCoordinator.class);
    private final ProjectFileRoleDecisionRepository roleDecisions =
            mock(ProjectFileRoleDecisionRepository.class);
    private final EffectiveDocumentRoleResolver effectiveRoles = mock(EffectiveDocumentRoleResolver.class);
    private final ContractCandidateMaterializationService candidateMaterializer =
            mock(ContractCandidateMaterializationService.class);
    private final ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
    private final ProjectFileIdentityProjectionService identityProjections =
            mock(ProjectFileIdentityProjectionService.class);
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "tester", "Tester",
            Set.of("AUDITOR"));
    private final ProjectFile file = new ProjectFile(fileId, projectId, null, null, "contract.pdf",
            "application/pdf", ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
            ProjectFileParseStatus.PARSED, SHA, SHA, 10, "contract", null, NOW, NOW);
    private final ProjectFileIdentityDecision decision = new ProjectFileIdentityDecision(decisionId,
            projectId, 1, fileId, SHA, parseId, 1, assessmentId,
            ProjectIdentityResolution.CONFIRM_SAME_PROJECT, actor.userId(), NOW, null, null, 1);
    private final IdentityConfirmationResult confirmed = new IdentityConfirmationResult(decision, false);
    private final IdentityConfirmationService service = new IdentityConfirmationService(files, transactions,
            roleDecisions, effectiveRoles, candidateMaterializer, candidates, identityProjections,
            () -> Optional.of(actor), Clock.fixed(NOW, ZoneOffset.UTC), true);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(2)).get())
                .when(files).withFileAuthorityFence(eq(projectId), eq(fileId), any());
        when(files.findByProjectIdAndId(projectId, fileId)).thenReturn(Optional.of(file));
        when(transactions.confirm(any(), eq(actor), any(), eq(NOW))).thenReturn(confirmed);
        when(transactions.reopen(projectId, fileId, decisionId, false)).thenReturn(confirmed);
        when(identityProjections.project(file)).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, assessmentId, 1L,
                ProjectIdentityAssessmentStatus.SAME_PROJECT, ProjectIdentityDecisionStatus.CONFIRMED,
                decisionId, 1L, true, false, decision, null));
        when(effectiveRoles.resolve(file)).thenReturn(new EffectiveDocumentRoleResult(
                DocumentRole.CONTRACT_BODY, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                roleDecisionId, 1L, parseId, 1));
        when(candidates.findBySourceContractFileId(projectId, fileId)).thenReturn(Optional.empty());
    }

    @Test
    void sameProjectContractWaitsForFactExtractionBeforeCandidateMaterialization() {
        var result = service.confirm(new IdentityConfirmationCommand(projectId, fileId, assessmentId,
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, 0, null, "fact-gate",
                new ProjectRequestMetadata("request", "trace", null)));

        assertThat(result.candidateId()).isNull();
        assertThat(result.candidateMaterializationStatus())
                .isEqualTo(ContractCandidateMaterializationStatus.WAITING_FOR_FACT_EXTRACTION);
        verify(candidateMaterializer, never()).materializeFromDecision(any(), any(), any(), any());
    }

    @Test
    void reopenedDecisionPreservesFactExtractionWaitWithoutCreatingCandidate() {
        var reopened = service.get(projectId, fileId, decisionId);

        assertThat(reopened.candidateId()).isNull();
        assertThat(reopened.candidateMaterializationStatus())
                .isEqualTo(ContractCandidateMaterializationStatus.WAITING_FOR_FACT_EXTRACTION);
        verify(candidateMaterializer, never()).materializeFromDecision(any(), any(), any(), any());
    }
}
