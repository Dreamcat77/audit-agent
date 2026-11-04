package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractSnapshotLockPort;
import com.fuquan.audit.contract.application.reviewrun.port.ProjectContractSnapshotLockPort;
import com.fuquan.audit.contract.application.project.ContractCandidateConfirmationPayloadCanonicalizer;
import com.fuquan.audit.contract.application.project.ConfirmedContractPayload;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.text.Normalizer;
import org.springframework.stereotype.Component;

/** Resolves only selected, current ProjectFile facts inside the short run-create transaction. */
@Component
public class ReviewRunReadinessResolver {
    public record Ready(Contract contract, UUID sourceCandidateId, UUID sourceConfirmationId,
            ContractSnapshotCanonicalizer.Canonicalized contractSnapshot,
            List<ContractReviewRunMaterial> materials) {
        public Ready { materials = List.copyOf(materials); }
    }

    private final ProjectRepository projects;
    private final ProjectContractSnapshotLockPort associations;
    private final ContractSnapshotLockPort contracts;
    private final ContractCandidateRepository candidates;
    private final ContractCandidateConfirmationRepository confirmations;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleDecisionRepository roleDecisions;
    private final EffectiveDocumentRoleResolver roles;
    private final EffectiveProjectIdentityResolver identities;
    private final ContractSnapshotCanonicalizer canonicalizer;

    public ReviewRunReadinessResolver(ProjectRepository projects, ProjectContractSnapshotLockPort associations,
            ContractSnapshotLockPort contracts, ContractCandidateRepository candidates,
            ContractCandidateConfirmationRepository confirmations, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileRoleDecisionRepository roleDecisions,
            EffectiveDocumentRoleResolver roles, EffectiveProjectIdentityResolver identities,
            ContractSnapshotCanonicalizer canonicalizer) {
        this.projects = projects; this.associations = associations; this.contracts = contracts;
        this.candidates = candidates; this.confirmations = confirmations; this.files = files;
        this.parses = parses; this.roleDecisions = roleDecisions; this.roles = roles;
        this.identities = identities; this.canonicalizer = canonicalizer;
    }

    public Ready resolve(UUID runId, UUID projectId, UUID contractId, long expectedContractVersion,
            List<UUID> materialFileIds, String actorId, Instant now) {
        if (expectedContractVersion < 0 || materialFileIds == null || materialFileIds.isEmpty()
                || materialFileIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(materialFileIds).size() != materialFileIds.size()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid material selection");
        }
        List<UUID> sorted = materialFileIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        List<ProjectFile> lockedFiles = new ArrayList<>();
        for (UUID fileId : sorted) {
            lockedFiles.add(files.findByProjectIdAndIdForUpdate(projectId, fileId)
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found")));
        }
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        if (!project.metadataConfirmed()) throw conflict("project metadata is not confirmed");
        var association = associations.findByContractIdForUpdate(contractId)
                .filter(value -> value.projectId().equals(projectId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract not found"));
        Contract contract = contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract not found"));
        if (contract.versionNo() != expectedContractVersion || !"READY_FOR_REVIEW".equals(contract.status()))
            throw conflict("contract version or status is not ready");
        ContractCandidate candidate = candidates.findByProjectIdAndIdForUpdate(projectId, association.candidateId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found"));
        if (candidate.promotionState() != CandidatePromotionState.PROMOTED
                || !contractId.equals(candidate.promotedContractId())
                || !candidate.projectId().equals(projectId)) throw conflict("candidate promotion is not authoritative");
        requirePromotionConfirmation(projectId, candidate, contract);
        if (!materialFileIds.contains(candidate.sourceContractFileId()))
            throw conflict("contract source file must be selected");

        List<ContractReviewRunMaterial> materials = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            UUID fileId = sorted.get(i);
            ProjectFile file = lockedFiles.get(i);
            if (file.category() == ProjectFileCategory.PACKAGE
                    || file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                    || file.parseStatus() != ProjectFileParseStatus.PARSED) throw conflict("material is not available and parsed");
            ProjectFileParseResult parse = parses.findLatestByFileId(fileId)
                    .orElseThrow(() -> conflict("material parse result is missing"));
            if (!parse.projectId().equals(projectId) || !parse.fileId().equals(fileId)
                    || !parse.fileSha256().equals(file.sha256())
                    || parse.parseStatus() != ProjectFileParseStatus.PARSED
                    || !Objects.equals(parse.canonicalText(), file.parsedText())
                    || !ContractSnapshotCanonicalizer.sha256(parse.canonicalText()).equals(parse.canonicalTextSha256()))
                throw conflict("material parse result is not current");
            var role = roles.resolve(file);
            if (!parse.parseResultId().equals(role.parseResultId())
                    || role.parseVersion() == null || parse.parseVersion() != role.parseVersion()
                    || role.effectiveRole() == DocumentRole.UNKNOWN) throw conflict("material role is not authoritative");
            UUID roleDecisionId = role.roleDecisionId();
            if (role.source().isAuthoritativeDecision()) {
                ProjectFileRoleDecision decision = roleDecisions.findById(roleDecisionId)
                        .orElseThrow(() -> conflict("material role decision is missing"));
                if (!decision.projectId().equals(projectId) || !decision.fileId().equals(fileId)
                        || !decision.fileSha256().equals(file.sha256())
                        || !decision.parseResultId().equals(parse.parseResultId())
                        || decision.parseVersion() != parse.parseVersion()
                        || decision.versionNo() != role.decisionVersionNo()
                        || decision.selectedRole() != role.effectiveRole())
                    throw conflict("material role decision is stale");
            } else if (role.source() != EffectiveDocumentRoleSource.LEGACY_EFFECTIVE
                    || parse.parseOrigin() != ProjectFileParseOrigin.LEGACY_IMPORTED || roleDecisionId != null) {
                throw conflict("material role requires human confirmation");
            }
            var identity = identities.resolve(project, file);
            if (!identity.eligibleForProjectUse() || !identity.identityCurrent()
                    || identity.excludedFromProjectUse())
                throw conflict("material project identity is not human confirmed");
            var decision = identity.currentHumanSameDecision();
            if (!decision.decisionId().equals(identity.decisionId())
                    || identity.decisionVersion() == null
                    || decision.versionNo() != identity.decisionVersion()
                    || !decision.projectId().equals(projectId) || decision.projectVersionNo() != project.versionNo()
                    || !decision.fileId().equals(fileId) || !decision.fileSha256().equals(file.sha256())
                    || !decision.parseResultId().equals(parse.parseResultId())
                    || decision.parseVersion() != parse.parseVersion()
                    || decision.resolution() != ProjectIdentityResolution.CONFIRM_SAME_PROJECT)
                throw conflict("material identity decision is stale");
            if (fileId.equals(candidate.sourceContractFileId()) && role.effectiveRole() != DocumentRole.CONTRACT_BODY)
                throw conflict("contract source role is not CONTRACT_BODY");
            materials.add(new ContractReviewRunMaterial(UUID.randomUUID(), runId, i + 1, projectId,
                    fileId, file.sha256(), parse.parseResultId(), parse.parseVersion(),
                    parse.canonicalTextSha256(), role.effectiveRole(), roleDecisionId,
                    decision.decisionId(), null, actorId, now));
        }
        return new Ready(contract, candidate.id(), candidate.promotedConfirmationId(),
                canonicalizer.canonicalize(contract), materials);
    }

    private void requirePromotionConfirmation(UUID projectId, ContractCandidate candidate, Contract contract) {
        if (candidate.promotedConfirmationId() == null) return; // old promoted rows retain genuine NULL history
        var confirmation = confirmations.findByProjectAndCandidateAndId(projectId, candidate.id(),
                candidate.promotedConfirmationId()).orElseThrow(() -> conflict("promoted confirmation is missing"));
        if (!confirmation.projectId().equals(projectId) || !confirmation.candidateId().equals(candidate.id())
                || !confirmation.sourceFileId().equals(candidate.sourceContractFileId())
                || !Objects.equals(confirmation.sourceParseResultId(), candidate.sourceParseResultId())
                || !Objects.equals(confirmation.sourceRoleDecisionId(), candidate.sourceRoleDecisionId())
                || !Objects.equals(confirmation.sourceIdentityDecisionId(), candidate.sourceIdentityDecisionId())
                || confirmation.resultCandidateVersionNo() + 1 != candidate.versionNo())
            throw conflict("promoted confirmation and candidate disagree");
        ConfirmedContractPayload payload = new ContractCandidateConfirmationPayloadCanonicalizer()
                .decodeAndVerify(confirmation);
        if (!sameContractBusinessFacts(payload, contract))
            throw conflict("promoted confirmation and Contract facts disagree");
    }

    private static boolean sameContractBusinessFacts(ConfirmedContractPayload payload, Contract contract) {
        if (!Objects.equals(normalize(payload.contractNo()), normalize(contract.contractNo()))
                || !Objects.equals(normalize(payload.contractName()), normalize(contract.contractName()))
                || !Objects.equals(normalize(payload.contractType()), normalize(contract.contractType()))
                || !Objects.equals(normalize(payload.partyA()), normalize(contract.partyA()))
                || !Objects.equals(normalize(payload.partyB()), normalize(contract.partyB()))
                || !Objects.equals(normalize(payload.partyBCreditCode()), normalize(contract.partyBCreditCode()))
                || !Objects.equals(normalize(payload.amountInWords()), normalize(contract.amountInWords()))
                || payload.contractAmount().compareTo(contract.contractAmount().amount()) != 0
                || payload.lineItems().size() != contract.lineItems().size()) return false;
        var persistedLines = contract.lineItems().stream()
                .sorted(Comparator.comparingInt(ContractLineItem::sortNo)
                        .thenComparing(line -> normalize(line.itemKey()))).toList();
        for (int i = 0; i < persistedLines.size(); i++) {
            var expected = payload.lineItems().get(i);
            var actual = persistedLines.get(i);
            if (!Objects.equals(normalize(expected.itemKey()), normalize(actual.itemKey()))
                    || !Objects.equals(normalize(expected.itemName()), normalize(actual.itemName()))
                    || expected.sortNo() != actual.sortNo()
                    || expected.basis() != actual.basis()
                    || !sameDecimal(expected.quantity(), actual.quantity())
                    || !sameDecimal(expected.unitPrice(), actual.unitPrice())
                    || expected.declaredAmount().compareTo(actual.declaredAmount().amount()) != 0) return false;
        }
        var award = payload.awardNotice();
        var persisted = contract.awardNotice();
        if (award == null || persisted == null) return award == null && persisted == null;
        return Objects.equals(normalize(award.noticeNo()), normalize(persisted.noticeNo()))
                && Objects.equals(normalize(award.projectName()), normalize(persisted.projectName()))
                && Objects.equals(normalize(award.winningBidder()), normalize(persisted.winningBidder()))
                && Objects.equals(normalize(award.winningBidderCreditCode()),
                        normalize(persisted.winningBidderCreditCode()))
                && Objects.equals(award.winningDate(), persisted.winningDate())
                && Objects.equals(normalize(award.sourceRef()), normalize(persisted.sourceRef()))
                && award.winningAmount().compareTo(persisted.winningAmount().amount()) == 0;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean sameDecimal(java.math.BigDecimal left, java.math.BigDecimal right) {
        return left == null || right == null ? left == right : left.compareTo(right) == 0;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(BusinessErrorCode.CONFLICT, message);
    }
}
