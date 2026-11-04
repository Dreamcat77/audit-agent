package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractSnapshotLockPort;
import com.fuquan.audit.contract.application.reviewrun.port.ProjectContractSnapshotLockPort;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.ContractCandidateConfirmationPayloadCanonicalizer;
import com.fuquan.audit.contract.application.project.ConfirmedContractPayload;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResult;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewRunReadinessResolverTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectContractSnapshotLockPort associations = mock(ProjectContractSnapshotLockPort.class);
    private final ContractSnapshotLockPort contracts = mock(ContractSnapshotLockPort.class);
    private final ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
    private final ContractCandidateConfirmationRepository confirmations = mock(ContractCandidateConfirmationRepository.class);
    private final ProjectFileRepository files = mock(ProjectFileRepository.class);
    private final ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
    private final ProjectFileRoleDecisionRepository roleDecisions = mock(ProjectFileRoleDecisionRepository.class);
    private final EffectiveDocumentRoleResolver roles = mock(EffectiveDocumentRoleResolver.class);
    private final EffectiveProjectIdentityResolver identities = mock(EffectiveProjectIdentityResolver.class);
    private final ContractSnapshotCanonicalizer canonicalizer = new ContractSnapshotCanonicalizer();
    private final ReviewRunReadinessResolver resolver = new ReviewRunReadinessResolver(projects, associations,
            contracts, candidates, confirmations, files, parses, roleDecisions, roles, identities, canonicalizer);
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final String actor = UUID.randomUUID().toString();
    private final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID(), candidateId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID(), parseId = UUID.randomUUID(), roleId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final String sha = "a".repeat(64), text = "合同编号 C-1，双方确认金额 100 元。";

    @Test
    void selectsOnlyRequestedSourceFileAndFreezesExactHumanTuples() {
        fixture(false);
        var ready = resolver.resolve(runId, projectId, contractId, 0, List.of(fileId), actor, now);
        assertThat(ready.contract().id()).isEqualTo(contractId);
        assertThat(ready.sourceCandidateId()).isEqualTo(candidateId);
        assertThat(ready.sourceConfirmationId()).isNull();
        assertThat(ready.materials()).hasSize(1);
        var selected = ready.materials().get(0);
        assertThat(selected.fileId()).isEqualTo(fileId);
        assertThat(selected.parseResultId()).isEqualTo(parseId);
        assertThat(selected.fileSha256()).isEqualTo(sha);
        assertThat(selected.roleDecisionId()).isEqualTo(roleId);
        assertThat(selected.identityDecisionId()).isEqualTo(identityId);
        assertThat(selected.effectiveRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(selected.canonicalTextSha256()).isEqualTo(canonicalizer.sha256(text));
    }

    @Test
    void duplicateSelectionAndOmittedContractSourceFailClosed() {
        fixture(false);
        reject(List.of(fileId, fileId), BusinessErrorCode.INVALID_ARGUMENT);
        UUID otherFileId = UUID.randomUUID();
        when(files.findByProjectIdAndIdForUpdate(projectId, otherFileId)).thenReturn(Optional.of(new ProjectFile(
                otherFileId, projectId, null, "material2.pdf", "material2.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, sha, sha, 100, text, null, now, now)));
        reject(List.of(otherFileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void otherProjectAssociationAndWrongExpectedVersionCannotCreateRun() {
        fixture(false);
        when(associations.findByContractIdForUpdate(contractId)).thenReturn(Optional.of(new ProjectContract(
                UUID.randomUUID(), contractId, candidateId, actor, now)));
        reject(List.of(fileId), BusinessErrorCode.NOT_FOUND);
        fixture(false);
        assertThatThrownBy(() -> resolver.resolve(runId, projectId, contractId, 1, List.of(fileId), actor, now))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void onlyCurrentHumanSameProjectIsEligible() {
        fixture(false);
        var current = identityDecision();
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.LIKELY_SAME_PROJECT, null, null, null, null, null,
                false, false, null));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, identityId, 1L,
                true, false, current));
        assertThat(resolver.resolve(runId, projectId, contractId, 0, List.of(fileId), actor, now).materials()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = EffectiveProjectIdentityStatus.class,
            names = {"LIKELY_SAME_PROJECT", "CONFLICT", "INSUFFICIENT", "NOT_SAME_PROJECT"})
    void everyNonSameIdentityStateBlocksEvenWhenContractAndRoleAreValid(
            EffectiveProjectIdentityStatus status) {
        fixture(false);
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                status, null, null, null, null, null, false, false, null));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void otherProjectFileAndNonAvailableOrUnparsedSourceFailClosed() {
        fixture(false);
        when(files.findByProjectIdAndIdForUpdate(projectId, fileId)).thenReturn(Optional.empty());
        reject(List.of(fileId), BusinessErrorCode.NOT_FOUND);
        fixture(false);
        when(files.findByProjectIdAndIdForUpdate(projectId, fileId)).thenReturn(Optional.of(new ProjectFile(
                fileId, projectId, null, "001.pdf", "001.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.UPLOAD_FAILED,
                ProjectFileParseStatus.PARSED, sha, sha, 100, text, null, now, now)));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(files.findByProjectIdAndIdForUpdate(projectId, fileId)).thenReturn(Optional.of(new ProjectFile(
                fileId, projectId, null, "001.pdf", "001.pdf", "application/pdf",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PENDING, sha, sha, 100, text, null, now, now)));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void parseShaMismatchOrUnconfirmedCurrentPipelineRoleCannotEnterSnapshot() {
        fixture(false);
        when(parses.findLatestByFileId(fileId)).thenReturn(Optional.of(new ProjectFileParseResult(
                parseId, projectId, fileId, "b".repeat(64), 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "PDFBox", "1", text,
                canonicalizer.sha256(text), 1, 1L, null, null, now, now, 0)));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(roles.resolve(file())).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.CONTRACT_BODY,
                EffectiveDocumentRoleSource.LEGACY_EFFECTIVE, null, null, parseId, 1));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(roles.resolve(file())).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.UNKNOWN,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, roleId, 1L, parseId, 1));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void selectedMaterialsAreFrozenInLexicalFileIdOrderNotClientOrder() {
        fixture(false);
        UUID awardFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID awardParseId = UUID.randomUUID(), awardRoleId = UUID.randomUUID();
        UUID awardIdentityId = UUID.randomUUID();
        String awardText = "Synthetic award notice", awardSha = "b".repeat(64);
        var awardFile = new ProjectFile(awardFileId, projectId, null, "material2.pdf", "material2.pdf",
                "application/pdf", ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, awardSha, awardSha, 100, awardText, null, now, now);
        when(files.findByProjectIdAndIdForUpdate(projectId, awardFileId)).thenReturn(Optional.of(awardFile));
        when(parses.findLatestByFileId(awardFileId)).thenReturn(Optional.of(new ProjectFileParseResult(
                awardParseId, projectId, awardFileId, awardSha, 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "PDFBox", "1", awardText,
                canonicalizer.sha256(awardText), 1, 1L, null, null, now, now, 0)));
        when(roles.resolve(awardFile)).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.AWARD_NOTICE,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, awardRoleId, 1L, awardParseId, 1));
        when(roleDecisions.findById(awardRoleId)).thenReturn(Optional.of(new ProjectFileRoleDecision(
                awardRoleId, projectId, awardFileId, awardSha, awardParseId, 1, UUID.randomUUID(),
                DocumentRole.AWARD_NOTICE, RoleDecisionStatus.CONFIRMED, actor, now, null, null, 1)));
        var awardDecision = new ProjectFileIdentityDecision(awardIdentityId, projectId, 1, awardFileId,
                awardSha, awardParseId, 1, UUID.randomUUID(), ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
                actor, now, null, null, 1);
        when(identities.resolve(project(), awardFile)).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, awardIdentityId, 1L,
                true, false, awardDecision));
        var forward = resolver.resolve(runId, projectId, contractId, 0,
                List.of(fileId, awardFileId), actor, now).materials();
        var reverse = resolver.resolve(runId, projectId, contractId, 0,
                List.of(awardFileId, fileId), actor, now).materials();
        assertThat(forward.stream().map(ContractReviewRunMaterial::fileId).toList())
                .containsExactly(awardFileId, fileId);
        assertThat(reverse.stream().map(ContractReviewRunMaterial::fileId).toList())
                .containsExactly(awardFileId, fileId);
        assertThat(forward.stream().map(ContractReviewRunMaterial::ordinal).toList())
                .containsExactly(1, 2);
    }

    @Test
    void staleRoleDecisionTupleOrNonContractSourceBlocks() {
        fixture(false);
        when(roleDecisions.findById(roleId)).thenReturn(Optional.of(new ProjectFileRoleDecision(
                roleId, projectId, fileId, sha, UUID.randomUUID(), 1, UUID.randomUUID(),
                DocumentRole.CONTRACT_BODY, RoleDecisionStatus.CONFIRMED, actor, now, null, null, 1)));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(roles.resolve(file())).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.OTHER,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, roleId, 1L, parseId, 1));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void resolverDecisionVersionsMustBindExactCurrentHumanStreams() {
        fixture(false);
        when(roles.resolve(file())).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.CONTRACT_BODY,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, roleId, 2L, parseId, 1));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, identityId, 2L,
                true, false, identityDecision()));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void staleOrExcludedSameProjectDecisionCannotBeUsedForRunReadiness() {
        fixture(false);
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, identityId, 1L,
                false, false, identityDecision()));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
        fixture(false);
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, identityId, 1L,
                true, true, identityDecision()));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    @Test
    void explicitLegacyImportedBodyCanFreezeNullRoleDecisionButNotNullIdentityDecision() {
        fixture(true);
        var ready = resolver.resolve(runId, projectId, contractId, 0, List.of(fileId), actor, now);
        assertThat(ready.materials().get(0).roleDecisionId()).isNull();
        assertThat(ready.materials().get(0).identityDecisionId()).isEqualTo(identityId);
    }

    @Test
    void historicalPromotedCandidateReopensReviewRunWithoutSupportBackfill() {
        fixture(false);
        ContractCandidate historicalCandidate = ContractCandidate.draftWithAuthority(candidateId, projectId, fileId,
                parseId, roleId, identityId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "f".repeat(64), "C-1", "Sample Contract", "GOODS", "A", "B", null,
                new BigDecimal("100"), null, null, null, null, List.of(), now).promote(contractId, actor, now);
        Contract historicalContract = contract();
        ProjectContract historicalAssociation = new ProjectContract(projectId, contractId, candidateId, actor, now);
        when(associations.findByContractIdForUpdate(contractId)).thenReturn(Optional.of(historicalAssociation));
        when(candidates.findByProjectIdAndIdForUpdate(projectId, candidateId))
                .thenReturn(Optional.of(historicalCandidate));

        var ready = resolver.resolve(runId, projectId, contractId, 0, List.of(fileId), actor, now);

        assertThat(ready.sourceCandidateId()).isEqualTo(candidateId);
        assertThat(historicalCandidate.id()).isEqualTo(candidateId);
        assertThat(historicalCandidate.projectId()).isEqualTo(projectId);
        assertThat(historicalCandidate.sourceContractFileId()).isEqualTo(fileId);
        assertThat(historicalCandidate.sourceParseResultId()).isEqualTo(parseId);
        assertThat(historicalCandidate.sourceRoleDecisionId()).isEqualTo(roleId);
        assertThat(historicalCandidate.sourceIdentityDecisionId()).isEqualTo(identityId);
        assertThat(historicalCandidate.sourceFactFingerprint()).isEqualTo("f".repeat(64));
        assertThat(historicalCandidate.versionNo()).isEqualTo(1L);
        assertThat(historicalCandidate.promotedContractId()).isEqualTo(contractId);
        assertThat(historicalAssociation.projectId()).isEqualTo(projectId);
        assertThat(historicalAssociation.contractId()).isEqualTo(contractId);
        assertThat(historicalAssociation.candidateId()).isEqualTo(candidateId);
        assertThat(historicalAssociation.createdBy()).isEqualTo(actor);
        assertThat(historicalAssociation.createdAt()).isEqualTo(now);
        assertThat(ready.contract()).isEqualTo(historicalContract);
        assertThat(ready.contract().versionNo()).isZero();
        assertThat(ready.materials()).singleElement().satisfies(material -> {
            assertThat(material.fileId()).isEqualTo(fileId);
            assertThat(material.parseResultId()).isEqualTo(parseId);
            assertThat(material.fileSha256()).isEqualTo(sha);
            assertThat(material.roleDecisionId()).isEqualTo(roleId);
            assertThat(material.identityDecisionId()).isEqualTo(identityId);
            assertThat(material.effectiveRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
            assertThat(material.canonicalTextSha256()).isEqualTo(canonicalizer.sha256(text));
        });
        verify(associations).findByContractIdForUpdate(contractId);
    }

    @Test
    void newPromotedCandidateNeedsMatchingDurableConfirmationAndContractBusinessFacts() {
        fixture(false);
        UUID confirmationId = UUID.randomUUID();
        var promoted = ContractCandidate.draftWithAuthority(candidateId, projectId, fileId,
                parseId, roleId, identityId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "f".repeat(64), "C-1", "Sample Contract", "GOODS", "A", "B", null,
                new BigDecimal("100"), null, null, null, null, List.of(), now)
                .confirmed(now).promote(contractId, confirmationId, actor, now);
        when(candidates.findByProjectIdAndIdForUpdate(projectId, candidateId)).thenReturn(Optional.of(promoted));
        when(confirmations.findByProjectAndCandidateAndId(projectId, candidateId, confirmationId))
                .thenReturn(Optional.of(confirmation(confirmationId)));
        assertThat(resolver.resolve(runId, projectId, contractId, 0, List.of(fileId), actor, now)
                .sourceConfirmationId()).isEqualTo(confirmationId);
        when(contracts.findByIdForUpdate(contractId)).thenReturn(Optional.of(new Contract(contractId, "C-1",
                "Sample Contract", "GOODS", "A", "B", null, Money.of(new BigDecimal("101")), null,
                "READY_FOR_REVIEW", 0, now, now, List.of(), null)));
        reject(List.of(fileId), BusinessErrorCode.CONFLICT);
    }

    private void reject(List<UUID> materialIds, BusinessErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(runId, projectId, contractId, 0, materialIds, actor, now))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode()).isEqualTo(code));
    }

    private void fixture(boolean legacy) {
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(project()));
        when(associations.findByContractIdForUpdate(contractId)).thenReturn(Optional.of(
                new ProjectContract(projectId, contractId, candidateId, actor, now)));
        when(contracts.findByIdForUpdate(contractId)).thenReturn(Optional.of(contract()));
        when(candidates.findByProjectIdAndIdForUpdate(projectId, candidateId)).thenReturn(Optional.of(candidate()));
        when(files.findByProjectIdAndIdForUpdate(projectId, fileId)).thenReturn(Optional.of(file()));
        when(parses.findLatestByFileId(fileId)).thenReturn(Optional.of(parse(legacy)));
        when(roles.resolve(file())).thenReturn(new EffectiveDocumentRoleResult(DocumentRole.CONTRACT_BODY,
                legacy ? EffectiveDocumentRoleSource.LEGACY_EFFECTIVE : EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                legacy ? null : roleId, legacy ? null : 1L, parseId, 1));
        when(roleDecisions.findById(roleId)).thenReturn(Optional.of(roleDecision()));
        when(identities.resolve(project(), file())).thenReturn(new EffectiveProjectIdentity(
                EffectiveProjectIdentityStatus.SAME_PROJECT, null, null, null, identityId, 1L,
                true, false, identityDecision()));
    }

    private Project project() { return new Project(projectId, "Sample Project", "P-1", "GOODS", actor, 1, now, now); }
    private Contract contract() { return new Contract(contractId, "C-1", "Sample Contract", "GOODS", "A", "B",
            null, Money.of(new BigDecimal("100.00")), null, "READY_FOR_REVIEW", 0, now, now, List.of(), null); }
    private ContractCandidate candidate() { return ContractCandidate.draft(candidateId, projectId, fileId,
            "C-1", "Sample Contract", "GOODS", "A", "B", null, new BigDecimal("100"), null,
            null, null, null, List.of(), now).promote(contractId, actor, now); }
    private ProjectFile file() { return new ProjectFile(fileId, projectId, null, "001.pdf", "001.pdf", "application/pdf",
            ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
            sha, sha, 100, text, null, now, now); }
    private ProjectFileParseResult parse(boolean legacy) { return new ProjectFileParseResult(parseId, projectId, fileId,
            sha, 1, legacy ? ProjectFileParseOrigin.LEGACY_IMPORTED : ProjectFileParseOrigin.CURRENT_PIPELINE,
            ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "PDFBox", "1", text,
            canonicalizer.sha256(text), 1, 1L, null, null, now, now, 0); }
    private ProjectFileRoleDecision roleDecision() { return new ProjectFileRoleDecision(roleId, projectId, fileId,
            sha, parseId, 1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, RoleDecisionStatus.CONFIRMED,
            actor, now, null, null, 1); }
    private ProjectFileIdentityDecision identityDecision() { return new ProjectFileIdentityDecision(identityId,
            projectId, 1, fileId, sha, parseId, 1, UUID.randomUUID(), ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
            actor, now, null, null, 1); }

    private ContractCandidateConfirmation confirmation(UUID confirmationId) {
        var canonical = new ContractCandidateConfirmationPayloadCanonicalizer();
        var payload = canonical.canonicalize(new ConfirmedContractPayload("C-1", "Sample Contract", "GOODS",
                "A", "B", null, new BigDecimal("100.00"), null, List.of(), null));
        String fingerprint = canonical.fingerprint(projectId, candidateId, fileId, parseId,
                roleId, identityId, 0, payload.schemaVersion(), payload.json());
        return new ContractCandidateConfirmation(confirmationId, projectId, candidateId, 0, 1,
                fileId, parseId, roleId, identityId, payload.schemaVersion(), fingerprint, payload.json(), actor,
                now, null, now);
    }
}
