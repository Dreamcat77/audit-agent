package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import com.fuquan.audit.contract.application.project.port.ProjectContractCommandPort;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContractCandidatePromotionTransactionCoordinator {
    static final String SCOPE = "PROJECT_CONTRACT_CANDIDATE_PROMOTE";
    private final ProjectRepository projects;
    private final ContractCandidateRepository candidates;
    private final ProjectFileRepository files;
    private final CandidateSourceAuthorityValidator sourceAuthority;
    private final ContractCandidateSupportAuthorityRepository supports;
    private final ContractCandidateConfirmationRepository confirmations;
    private final ContractCandidateConfirmationPayloadCanonicalizer canonicalizer;
    private final ContractCreationPort contractCreation;
    private final ProjectContractCommandPort associationCommands;
    private final ProjectContractQueryPort associationQueries;
    private final ContractRepository contractReads;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;
    private final FlowDebugLogger flowDebug;

    public ContractCandidatePromotionTransactionCoordinator(ProjectRepository projects,
            ContractCandidateRepository candidates, ProjectFileRepository files,
            CandidateSourceAuthorityValidator sourceAuthority,
            ContractCandidateSupportAuthorityRepository supports,
            ContractCandidateConfirmationRepository confirmations,
            ContractCandidateConfirmationPayloadCanonicalizer canonicalizer,
            ContractCreationPort contractCreation,
            ProjectContractCommandPort associationCommands, ProjectContractQueryPort associationQueries,
            ContractRepository contractReads, IdempotencyPort idempotency, OperationAuditPort audit,
            Clock clock, FlowDebugLogger flowDebug) {
        this.projects = projects;
        this.candidates = candidates;
        this.files = files;
        this.sourceAuthority = sourceAuthority;
        this.supports = supports;
        this.confirmations = confirmations;
        this.canonicalizer = canonicalizer;
        this.contractCreation = contractCreation;
        this.associationCommands = associationCommands;
        this.associationQueries = associationQueries;
        this.contractReads = contractReads;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.flowDebug = flowDebug;
    }

    sealed interface Preparation permits CompletedPromotion, FreshPromotion { }
    record CompletedPromotion(ContractCandidatePromotionResult result) implements Preparation { }
    record FreshPromotion(ContractCandidateConfirmation confirmation, ConfirmedContractPayload payload,
            String fingerprint, ContractCandidate candidate, List<ContractCandidateSupportAuthority> supports,
            List<UUID> fileIds) implements Preparation { }

    /** No transaction/session may carry discovery reads into the locked write transaction. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Preparation prepare(
            PromoteContractCandidateCommand command, CurrentUser actor) {
        ContractCandidateConfirmation confirmation = confirmations.findByProjectAndCandidateAndId(
                command.projectId(), command.candidateId(), command.confirmationId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "candidate confirmation not found"));
        ConfirmedContractPayload persistedPayload = canonicalizer.decodeAndVerify(confirmation);
        String fingerprint = ContractCandidatePromotionService.fingerprint(actor, command, confirmation);
        var prior = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (prior.isPresent()) return new CompletedPromotion(replay(prior.get(), command, actor, fingerprint));

        ContractCandidate discovered = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found"));
        List<ContractCandidateSupportAuthority> discoveredSupports = currentSupportRows(command);
        List<UUID> fileIds = Stream.concat(Stream.of(discovered.sourceContractFileId()),
                discoveredSupports.stream().map(ContractCandidateSupportAuthority::sourceFileId))
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        return new FreshPromotion(confirmation, persistedPayload, fingerprint, discovered, discoveredSupports, fileIds);
    }

    /** Also isolates an ambient caller's MyBatis SESSION cache; the service invokes this through the proxy. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized ContractCandidatePromotionResult promote(PromoteContractCandidateCommand command,
            CurrentUser actor, FreshPromotion prepared) {
        List<UUID> fileIds = prepared.fileIds();
        return withFileFences(command.projectId(), fileIds, 0, () -> promoteLocked(command, actor,
                prepared.confirmation(), prepared.payload(), prepared.fingerprint(), prepared.candidate(),
                prepared.supports(), fileIds));
    }

    private ContractCandidatePromotionResult promoteLocked(PromoteContractCandidateCommand command, CurrentUser actor,
            ContractCandidateConfirmation confirmation, ConfirmedContractPayload persistedPayload, String fingerprint,
            ContractCandidate discovered, List<ContractCandidateSupportAuthority> discoveredSupports, List<UUID> fileIds) {
        ContractCandidate candidate = requireProjectAndCandidate(command, fileIds);
        var winner = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (winner.isPresent()) return replay(winner.get(), command, actor, fingerprint);
        requireDraftCandidate(candidate, command);
        requireUnchangedDiscovery(candidate, discovered, discoveredSupports, command);
        requireCurrentConfirmation(candidate, confirmation, command);
        sourceAuthority.snapshotWithCurrentSupport(candidate).requirePromotable();
        UUID proposedContractId = UUID.randomUUID();
        Instant now = clock.instant();
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, command.idempotencyKey(), actor.userId(), "CONTRACT_CANDIDATE",
                command.candidateId().toString(), fingerprint, "CONTRACT", proposedContractId.toString(),
                safe(command.metadata().requestId()), safe(command.metadata().traceId()), now));
        if (!claim.acquired()) return replay(claim.record(), command, actor, fingerprint);

        flowDebug.log("PROJECT_PROMOTION_TX_ENTER", "CONTRACT", "APPLICATION", "promote",
                Map.of("projectId", command.projectId(), "candidateId", command.candidateId()));
        try {
            Contract contract = formalContract(proposedContractId, persistedPayload, now);
            contractCreation.insertCompleteSnapshot(contract);
            ProjectContract association = new ProjectContract(command.projectId(), contract.id(),
                    command.candidateId(), actor.userId(), now);
            associationCommands.insert(association);
            ContractCandidate promoted = candidates.promoteIfVersionMatches(command.projectId(),
                    command.candidateId(), command.expectedVersion(), contract.id(),
                    confirmation.confirmationId(), actor.userId(), now);
            audit.append(successAudit(command, actor, contract, now));
            idempotency.markSucceeded(claim.record(), "CONTRACT", contract.id().toString(), now);
            flowDebug.log("PROJECT_PROMOTION_TX_COMMIT", "CONTRACT", "APPLICATION", "promote",
                    Map.of("projectId", command.projectId(), "candidateId", command.candidateId(),
                            "contractId", contract.id(), "status", "SUCCESS"));
            return new ContractCandidatePromotionResult(contract, association, promoted);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            flowDebug.log("PROJECT_PROMOTION_CONFLICT", "CONTRACT", "APPLICATION", "promote",
                    Map.of("projectId", command.projectId(), "candidateId", command.candidateId(),
                            "reasonCode", failure.getClass().getSimpleName()));
            throw failure;
        }
    }

    private ContractCandidate requireProjectAndCandidate(PromoteContractCandidateCommand command, List<UUID> fileIds) {
        for (UUID fileId : fileIds) {
            files.findByProjectIdAndIdForUpdate(command.projectId(), fileId)
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                            "candidate authority file not found"));
        }
        if (projects.findByIdForUpdate(command.projectId()).isEmpty()) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found");
        }
        return candidates.findByProjectIdAndIdForUpdate(command.projectId(), command.candidateId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "contract candidate not found"));
    }

    private <T> T withFileFences(UUID projectId, List<UUID> fileIds, int index, Supplier<T> operation) {
        if (index == fileIds.size()) return operation.get();
        // Local command fences use exactly the row-lock order; DM8 retains the row locks until commit.
        return files.withFileAuthorityFence(projectId, fileIds.get(index),
                () -> withFileFences(projectId, fileIds, index + 1, operation));
    }

    private List<ContractCandidateSupportAuthority> currentSupportRows(PromoteContractCandidateCommand command) {
        var rows = supports.findCurrentByProjectIdAndCandidateId(command.projectId(), command.candidateId());
        if (rows == null || rows.stream().anyMatch(row -> row == null
                || !row.projectId().equals(command.projectId()) || !row.candidateId().equals(command.candidateId()))) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate support cohort cannot be proven");
        }
        return rows.stream().sorted(Comparator.comparing((ContractCandidateSupportAuthority row) -> row.sourceFileId().toString())
                .thenComparing(row -> row.supportAuthorityId().toString())).toList();
    }

    private void requireUnchangedDiscovery(ContractCandidate candidate, ContractCandidate discovered,
            List<ContractCandidateSupportAuthority> discoveredSupports, PromoteContractCandidateCommand command) {
        if (candidate.versionNo() != discovered.versionNo()
                || candidate.promotionState() != discovered.promotionState()
                || !candidate.sourceContractFileId().equals(discovered.sourceContractFileId())
                || !Objects.equals(candidate.sourceParseResultId(), discovered.sourceParseResultId())
                || !Objects.equals(candidate.sourceRoleDecisionId(), discovered.sourceRoleDecisionId())
                || !Objects.equals(candidate.sourceIdentityDecisionId(), discovered.sourceIdentityDecisionId())
                || candidate.sourceAuthoritySource() != discovered.sourceAuthoritySource()
                || !Objects.equals(candidate.sourceFactFingerprint(), discovered.sourceFactFingerprint())
                || !Objects.equals(candidate.supportAuthorityVersionNo(), discovered.supportAuthorityVersionNo())
                || !Objects.equals(candidate.supportAuthorityFingerprint(), discovered.supportAuthorityFingerprint())
                || !currentSupportRows(command).equals(discoveredSupports)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion authority changed while locking");
        }
    }

    private static void requireDraftCandidate(ContractCandidate candidate,
                                              PromoteContractCandidateCommand command) {
        if (candidate.promotionState() != CandidatePromotionState.DRAFT
                || candidate.versionNo() != command.expectedVersion()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion conflict");
        }
    }

    private static void requireCurrentConfirmation(ContractCandidate candidate,
            ContractCandidateConfirmation confirmation, PromoteContractCandidateCommand command) {
        if (confirmation.resultCandidateVersionNo() != command.expectedVersion()
                || !confirmation.projectId().equals(candidate.projectId())
                || !confirmation.candidateId().equals(candidate.id())
                || !confirmation.sourceFileId().equals(candidate.sourceContractFileId())
                || !java.util.Objects.equals(confirmation.sourceParseResultId(), candidate.sourceParseResultId())
                || !java.util.Objects.equals(confirmation.sourceRoleDecisionId(), candidate.sourceRoleDecisionId())
                || !java.util.Objects.equals(confirmation.sourceIdentityDecisionId(),
                        candidate.sourceIdentityDecisionId())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate confirmation is stale");
        }
    }

    private ContractCandidatePromotionResult replay(IdempotencyRecord record,
            PromoteContractCandidateCommand command, CurrentUser actor, String fingerprint) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !command.candidateId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        if (record.status() != IdempotencyStatus.SUCCEEDED || !"CONTRACT".equals(record.resultResourceType())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    record.status() == IdempotencyStatus.IN_PROGRESS
                            ? "contract promotion is in progress"
                            : "idempotent contract promotion previously failed");
        }
        UUID contractId;
        try {
            contractId = UUID.fromString(record.resultResourceId());
        } catch (RuntimeException invalid) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "promotion result identity is invalid");
        }
        Contract contract = contractReads.findById(contractId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "promoted contract durable state is missing"));
        ProjectContract association = associationQueries.findByCandidateId(command.candidateId())
                .filter(value -> value.projectId().equals(command.projectId()) && value.contractId().equals(contractId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "promoted association durable state is missing"));
        ContractCandidate candidate = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                .filter(value -> value.promotionState() == CandidatePromotionState.PROMOTED
                        && contractId.equals(value.promotedContractId())
                        && command.confirmationId().equals(value.promotedConfirmationId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "promoted candidate durable state is missing"));
        return new ContractCandidatePromotionResult(contract, association, candidate);
    }

    private static Contract formalContract(UUID contractId, ConfirmedContractPayload payload, Instant now) {
        List<ContractLineItem> lines = payload.lineItems().stream().map(line -> new ContractLineItem(
                UUID.randomUUID(), contractId, line.itemKey(), line.itemName(), line.basis(),
                line.quantity(), line.unitPrice(),
                Money.of(line.declaredAmount()), line.sortNo())).toList();
        AwardNoticeReference award = payload.awardNotice() == null ? null : new AwardNoticeReference(
                UUID.randomUUID(), contractId, payload.awardNotice().noticeNo(), payload.awardNotice().projectName(),
                payload.awardNotice().winningBidder(), payload.awardNotice().winningBidderCreditCode(),
                Money.of(payload.awardNotice().winningAmount()), payload.awardNotice().winningDate(),
                payload.awardNotice().sourceRef());
        return new Contract(contractId, payload.contractNo(), payload.contractName(), payload.contractType(),
                payload.partyA(), payload.partyB(), payload.partyBCreditCode(), Money.of(payload.contractAmount()),
                payload.amountInWords(), "READY_FOR_REVIEW", 0, now, now, lines, award);
    }

    private OperationAuditEvent successAudit(PromoteContractCandidateCommand command, CurrentUser actor,
                                              Contract contract, Instant now) {
        String detail = "{\"lineCount\":" + contract.lineItems().size()
                + ",\"awardPresent\":" + (contract.awardNotice() != null) + "}";
        return new OperationAuditEvent("PROJECT_CONTRACT_CANDIDATE_PROMOTED", "CONTRACT", "promote",
                actor.userId(), actor.username(), "CONTRACT_CANDIDATE", command.candidateId().toString(), null,
                OperationAuditResultStatus.SUCCESS, "SUCCESS", "promote contract candidate", detail,
                safe(command.metadata().requestId()), safe(command.metadata().traceId()),
                command.metadata().sourceIp(), now);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }
}
