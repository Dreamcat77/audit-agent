package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.contract.application.project.factai.*;
import com.fuquan.audit.contract.application.project.identity.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.project.role.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AutomaticProjectFileClassificationServiceTest {
    final UUID project = UUID.randomUUID(), fileId = UUID.randomUUID(), parse = UUID.randomUUID();
    final UUID roleId = UUID.randomUUID(), identityId = UUID.randomUUID();
    final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "synthetic", "Synthetic", Set.of("AUDITOR"));
    final ProjectRequestMetadata metadata = new ProjectRequestMetadata("test-request", "test-trace", null);
    final ProjectFileRepository files = mock(ProjectFileRepository.class);
    final ProjectFileRoleProjectionService roles = mock(ProjectFileRoleProjectionService.class);
    final ProjectFileIdentityProjectionService identities = mock(ProjectFileIdentityProjectionService.class);
    final RoleConfirmationService roleConfirmations = mock(RoleConfirmationService.class);
    final IdentityConfirmationService identityConfirmations = mock(IdentityConfirmationService.class);
    final ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
    final ContractCandidateConfirmationService candidateConfirmations = mock(ContractCandidateConfirmationService.class);
    final ContractCandidatePromotionService promotions = mock(ContractCandidatePromotionService.class);
    final ProjectIntakeProgressionService intake = mock(ProjectIntakeProgressionService.class);
    final ContractFactExtractionService extraction = mock(ContractFactExtractionService.class);
    final ContractFactExtractionRepository attempts = mock(ContractFactExtractionRepository.class);
    final ContractFactCandidatePort factPort = mock(ContractFactCandidatePort.class);
    final ContractCandidateMaterializationService materialization = mock(ContractCandidateMaterializationService.class);
    final ProjectFileRoleDecisionRepository decisions = mock(ProjectFileRoleDecisionRepository.class);
    final ContractCandidateConfirmationRepository confirmations = mock(ContractCandidateConfirmationRepository.class);
    final ProjectFile file = mock(ProjectFile.class);
    final ProjectFileRoleProjection role = mock(ProjectFileRoleProjection.class);
    final EffectiveProjectIdentity identity = mock(EffectiveProjectIdentity.class);

    AutomaticProjectFileClassificationService service(boolean factEnabled) {
        when(files.findByProjectId(project)).thenReturn(List.of(file));
        when(files.findByProjectIdAndId(project, fileId)).thenReturn(Optional.of(file));
        when(file.projectId()).thenReturn(project); when(file.id()).thenReturn(fileId);
        when(file.ingestionStatus()).thenReturn(ProjectFileIngestionStatus.AVAILABLE);
        when(file.parseStatus()).thenReturn(ProjectFileParseStatus.PARSED);
        when(roles.project(file)).thenReturn(role);
        when(role.roleAssessmentId()).thenReturn(UUID.randomUUID());
        when(role.roleExecutionStatus()).thenReturn("COMPLETED");
        when(role.roleDecisionId()).thenReturn(roleId);
        when(role.parseResultId()).thenReturn(parse);
        when(role.effectiveRoleSource()).thenReturn(EffectiveDocumentRoleSource.SYSTEM_AUTO_DECISION);
        when(role.effectiveRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(identities.project(file)).thenReturn(identity);
        when(identity.assessmentId()).thenReturn(UUID.randomUUID());
        when(identity.decisionId()).thenReturn(identityId);
        when(identity.eligibleForProjectUse()).thenReturn(true);
        when(identity.systemAutoDecision()).thenReturn(true);
        when(decisions.findById(roleId)).thenReturn(Optional.of(mock(ProjectFileRoleDecision.class)));
        when(candidates.findBySourceContractFileId(project, fileId)).thenReturn(Optional.empty());
        return new AutomaticProjectFileClassificationService(files, roles, identities, roleConfirmations,
                identityConfirmations, candidates, mock(ContractCandidateConfirmationPayloadCanonicalizer.class),
                candidateConfirmations, promotions, mock(AutomaticProjectMetadataService.class), intake,
                mock(AuthContext.class), true, factEnabled, extraction, attempts, factPort, materialization,
                decisions, confirmations);
    }

    @Test void resumesAfterRoleAndIdentityWereCommittedWithoutAskingForConfirmationAgain() {
        var service = service(false);
        service.advance(project, metadata, actor);
        verify(materialization).materializeFromDecision(any(), any(), eq(actor), eq(metadata));
        verifyNoInteractions(roleConfirmations, identityConfirmations, extraction);
    }

    @Test void neverPromotesHistoricHumanDecisionsAutomatically() {
        var service = service(false);
        when(role.effectiveRoleSource()).thenReturn(EffectiveDocumentRoleSource.HUMAN_CONFIRMATION);
        service.advance(project, metadata, actor);
        verifyNoInteractions(materialization, extraction, candidateConfirmations, promotions);
    }

    @Test void callsRealFactPipelineBeforeMaterializationWithStableAuthorityIdentity() {
        var service = service(true);
        when(extraction.extract(eq(project), eq(fileId), anyString(), eq(actor), eq(metadata)))
                .thenReturn(new ContractFactExtractionResult(UUID.randomUUID(), false, "SUCCEEDED", 6, 0));
        service.advance(project, metadata, actor);
        var order = inOrder(extraction, materialization);
        order.verify(extraction).extract(eq(project), eq(fileId), argThat(key -> key.length() <= 128
                && key.contains(parse.toString()) && key.contains(identityId.toString())), eq(actor), eq(metadata));
        order.verify(materialization).materializeFromDecision(any(), any(), eq(actor), eq(metadata));
    }

    @Test void terminalFactAttemptsDoNotResendAndStillAdvanceToAvailableMaterialFacts() {
        for (var state : List.of(ContractFactExtractionAttempt.Status.FAILED,
                ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN)) {
            reset(materialization, extraction);
            var service = service(true);
            var attempt = mock(ContractFactExtractionAttempt.class);
            when(attempt.status()).thenReturn(state);
            when(attempts.findLatestForAuthority(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Optional.of(attempt));
            service.advance(project, metadata, actor);
            verifyNoInteractions(extraction);
            verify(materialization).materializeFromDecision(any(), any(), eq(actor), eq(metadata));
        }
        // This is a separate invocation scenario: successful materialization in the
        // preceding terminal scenarios must not count as an IN_PROGRESS side effect.
        clearInvocations(extraction, materialization, promotions);
        var inProgressService = service(true);
        var inProgress = mock(ContractFactExtractionAttempt.class);
        when(inProgress.status()).thenReturn(ContractFactExtractionAttempt.Status.IN_PROGRESS);
        when(attempts.findLatestForAuthority(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(inProgress));
        inProgressService.advance(project, metadata, actor);
        verifyNoInteractions(extraction, materialization, promotions);
    }

    @Test void returnedModelFailureDoesNotBlockMaterializationOrTriggerPromotion() {
        var service = service(true);
        when(extraction.extract(eq(project), eq(fileId), anyString(), eq(actor), eq(metadata)))
                .thenReturn(new ContractFactExtractionResult(UUID.randomUUID(), false, "FAILED", 0, 2));

        service.advance(project, metadata, actor);

        verify(extraction).extract(eq(project), eq(fileId), anyString(), eq(actor), eq(metadata));
        verify(materialization).materializeFromDecision(any(), any(), eq(actor), eq(metadata));
        verifyNoInteractions(promotions, candidateConfirmations);
    }

    @Test void reusesCommittedConfirmationWhenPromotionWasInterrupted() {
        var service = service(false);
        var candidate = ContractCandidate.draftWithAuthority(UUID.randomUUID(), project, fileId, parse,
                roleId, identityId, ContractCandidateAuthoritySource.SYSTEM_AUTO, "a".repeat(64),
                "SYNTHETIC-1", "合成合同", "GOODS", "合成采购人", "合成供应商", null,
                new BigDecimal("10000.00"), null, null, null, null, List.of(), java.time.Instant.now())
                .confirmed(java.time.Instant.now());
        when(candidates.findBySourceContractFileId(project, fileId)).thenReturn(Optional.of(candidate));
        when(candidates.findByProjectIdAndId(project, candidate.id())).thenReturn(Optional.of(candidate));
        var confirmation = mock(ContractCandidateConfirmation.class);
        var confirmationId = UUID.randomUUID();
        when(confirmation.confirmationId()).thenReturn(confirmationId);
        when(confirmation.resultCandidateVersionNo()).thenReturn(candidate.versionNo());
        when(confirmation.sourceParseResultId()).thenReturn(parse);
        when(confirmation.sourceRoleDecisionId()).thenReturn(roleId);
        when(confirmation.sourceIdentityDecisionId()).thenReturn(identityId);
        when(confirmations.findLatestByProjectAndCandidate(project, candidate.id())).thenReturn(Optional.of(confirmation));
        service.advance(project, metadata, actor);
        verifyNoInteractions(candidateConfirmations);
        verify(promotions).promoteAutomatically(argThat(command -> command.confirmationId().equals(confirmationId)
                && command.expectedVersion() == candidate.versionNo()), eq(actor));
    }

    @Test void lowConfidenceAndConflictingRolesBecomeOther() {
        when(role.suggestedRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(role.roleConfidence()).thenReturn(new BigDecimal("0.69"));
        assertThat(AutomaticProjectFileClassificationService.reliableRole(role)).isEqualTo(DocumentRole.OTHER);
        when(role.roleConfidence()).thenReturn(new BigDecimal("0.99"));
        when(role.roleConflict()).thenReturn(true);
        assertThat(AutomaticProjectFileClassificationService.reliableRole(role)).isEqualTo(DocumentRole.OTHER);
    }

    @Test void filenameCategoryProvidesBoundedFallbackWhenBodyEvidenceIsWeak() {
        when(role.suggestedRole()).thenReturn(DocumentRole.UNKNOWN);
        when(role.roleConfidence()).thenReturn(BigDecimal.ZERO);
        assertThat(AutomaticProjectFileClassificationService.reliableRole(role,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY))
                .isEqualTo(DocumentRole.CONTRACT_BODY);
    }

    @Test void filenameCategoryMapsEverySupportedMaterialTypeWhenBodyEvidenceIsWeak() {
        when(role.roleConflict()).thenReturn(false);
        when(role.roleConfidence()).thenReturn(new BigDecimal("0.20"));
        var mappings = java.util.Map.of(
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY, DocumentRole.CONTRACT_BODY,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.AWARD_NOTICE, DocumentRole.AWARD_NOTICE,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.BID_RESPONSE, DocumentRole.BID_RESPONSE,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.TENDER_DOCUMENT, DocumentRole.TENDER_DOCUMENT,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.SUPPLEMENT, DocumentRole.SUPPLEMENT,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.OTHER, DocumentRole.OTHER);

        mappings.forEach((category, expected) -> {
            assertThat(AutomaticProjectFileClassificationService.reliableRole(role, category))
                    .as("filename category %s", category).isEqualTo(expected);
        });
    }

    @Test void strongBodyEvidenceWinsOverMisleadingFilenameCategory() {
        when(role.suggestedRole()).thenReturn(DocumentRole.TENDER_DOCUMENT);
        when(role.roleConfidence()).thenReturn(new BigDecimal("0.91"));
        assertThat(AutomaticProjectFileClassificationService.reliableRole(role,
                com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY))
                .isEqualTo(DocumentRole.TENDER_DOCUMENT);
    }
}
