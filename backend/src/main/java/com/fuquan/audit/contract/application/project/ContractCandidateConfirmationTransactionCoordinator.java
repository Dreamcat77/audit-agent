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
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContractCandidateConfirmationTransactionCoordinator {
    static final String SCOPE = "CONTRACT_CANDIDATE_CONFIRM";
    private final ProjectRepository projects;
    private final ContractCandidateRepository candidates;
    private final ProjectFileRepository files;
    private final CandidateSourceAuthorityValidator sourceAuthority;
    private final ContractCandidateSupportAuthorityRepository supports;
    private final ContractCandidateConfirmationRepository confirmations;
    private final ContractCandidateConfirmationPayloadCanonicalizer canonicalizer;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;
    private final FlowDebugLogger flowDebug;

    public ContractCandidateConfirmationTransactionCoordinator(ProjectRepository projects,
            ContractCandidateRepository candidates, ProjectFileRepository files,
            CandidateSourceAuthorityValidator sourceAuthority,
            ContractCandidateSupportAuthorityRepository supports,
            ContractCandidateConfirmationRepository confirmations,
            ContractCandidateConfirmationPayloadCanonicalizer canonicalizer,
            IdempotencyPort idempotency, OperationAuditPort audit, Clock clock, FlowDebugLogger flowDebug) {
        this.projects = projects; this.candidates = candidates; this.files = files;
        this.sourceAuthority = sourceAuthority; this.confirmations = confirmations;
        this.supports = supports;
        this.canonicalizer = canonicalizer; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock; this.flowDebug = flowDebug;
    }

    @Transactional
    public synchronized ContractCandidateConfirmationResult confirm(
            ConfirmContractCandidateCommand command, CurrentUser actor) {
        var canonical = canonicalizer.canonicalize(command.payload());
        // A finished operation is judged against its original immutable tuple, not current authority/version.
        var prior = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), command, actor, canonical);

        LockedCandidate locked = lockCandidate(command);
        // Another transaction may have completed this same operation while we waited for the source locks.
        var winner = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (winner.isPresent()) return replay(winner.get(), command, actor, canonical);
        ContractCandidate candidate = locked.candidate();
        if (candidate.promotionState() != CandidatePromotionState.DRAFT
                || candidate.versionNo() != command.expectedVersion()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate confirmation version conflict");
        }
        if (!candidate.sourceContractFileId().equals(locked.discovered().sourceContractFileId())
                || !Objects.equals(candidate.supportAuthorityVersionNo(), locked.discovered().supportAuthorityVersionNo())
                || !Objects.equals(candidate.supportAuthorityFingerprint(), locked.discovered().supportAuthorityFingerprint())
                || !currentSupportRows(command).equals(locked.discoveredSupports())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "candidate confirmation authority changed while locking");
        }
        CandidateSourceAuthority authority = sourceAuthority.snapshotWithCurrentSupport(candidate);
        authority.requirePromotable();
        requireCurrentHumanTuple(candidate, authority);
        Instant now = clock.instant();
        UUID confirmationId = UUID.randomUUID();
        String payloadFingerprint = canonicalizer.fingerprint(command.projectId(), command.candidateId(),
                candidate.sourceContractFileId(), candidate.sourceParseResultId(),
                candidate.sourceRoleDecisionId(), candidate.sourceIdentityDecisionId(),
                command.expectedVersion(), canonical.json());
        String fingerprint = commandFingerprint(actor, command, candidate.sourceContractFileId(),
                candidate.sourceParseResultId(), candidate.sourceRoleDecisionId(),
                candidate.sourceIdentityDecisionId(), payloadFingerprint);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(),
                actor.userId(), "CONTRACT_CANDIDATE", command.candidateId().toString(),
                fingerprint, "CONTRACT_CANDIDATE_CONFIRMATION", confirmationId.toString(),
                safe(command.metadata().requestId()), safe(command.metadata().traceId()), now));
        if (!claim.acquired()) return replay(claim.record(), command, actor, canonical);
        try {
            var previous = confirmations.findLatestByProjectAndCandidate(command.projectId(), command.candidateId());
            var confirmation = new ContractCandidateConfirmation(confirmationId, command.projectId(),
                    command.candidateId(), command.expectedVersion(), command.expectedVersion() + 1,
                    candidate.sourceContractFileId(), candidate.sourceParseResultId(),
                    candidate.sourceRoleDecisionId(), candidate.sourceIdentityDecisionId(),
                    canonical.schemaVersion(), payloadFingerprint, canonical.json(), actor.userId(),
                    now, previous.map(ContractCandidateConfirmation::confirmationId).orElse(null), now);
            confirmations.insert(confirmation);
            ContractCandidate updated = candidates.confirmDraftIfVersionMatches(command.projectId(),
                    command.candidateId(), command.expectedVersion(), now);
            if (updated.versionNo() != confirmation.resultCandidateVersionNo()) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "candidate confirmation result version cannot be proven");
            }
            audit.append(successAudit(command, actor, confirmation, now));
            idempotency.markSucceeded(claim.record(), "CONTRACT_CANDIDATE_CONFIRMATION",
                    confirmationId.toString(), now);
            flowDebug.log("PROJECT_CANDIDATE_CONFIRMATION_COMMIT", "CONTRACT", "APPLICATION", "confirm",
                    java.util.Map.of("projectId", command.projectId(), "candidateId", command.candidateId(),
                            "confirmationId", confirmationId));
            return new ContractCandidateConfirmationResult(confirmation, canonical.payload(), false);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    private LockedCandidate lockCandidate(ConfirmContractCandidateCommand command) {
        ContractCandidate discovered = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "contract candidate not found"));
        List<ContractCandidateSupportAuthority> discoveredSupports = currentSupportRows(command);
        List<UUID> fileIds = Stream.concat(Stream.of(discovered.sourceContractFileId()),
                discoveredSupports.stream().map(ContractCandidateSupportAuthority::sourceFileId))
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        for (UUID fileId : fileIds) {
            files.findByProjectIdAndIdForUpdate(command.projectId(), fileId)
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                            fileId.equals(discovered.sourceContractFileId())
                                    ? "candidate source file not found" : "candidate support file not found"));
        }
        projects.findByIdForUpdate(command.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ContractCandidate candidate = candidates.findByProjectIdAndIdForUpdate(command.projectId(), command.candidateId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "contract candidate not found"));
        return new LockedCandidate(discovered, candidate, discoveredSupports);
    }

    private List<ContractCandidateSupportAuthority> currentSupportRows(ConfirmContractCandidateCommand command) {
        var rows = supports.findCurrentByProjectIdAndCandidateId(command.projectId(), command.candidateId());
        if (rows == null || rows.stream().anyMatch(row -> row == null
                || !row.projectId().equals(command.projectId()) || !row.candidateId().equals(command.candidateId()))) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate support cohort cannot be proven");
        }
        return rows.stream().sorted(Comparator.comparing((ContractCandidateSupportAuthority row) -> row.sourceFileId().toString())
                .thenComparing(row -> row.supportAuthorityId().toString())).toList();
    }

    private record LockedCandidate(ContractCandidate discovered, ContractCandidate candidate,
            List<ContractCandidateSupportAuthority> discoveredSupports) { }

    private static void requireCurrentHumanTuple(ContractCandidate candidate, CandidateSourceAuthority authority) {
        if ((candidate.sourceAuthoritySource() != ContractCandidateAuthoritySource.HUMAN_CONFIRMATION
                && candidate.sourceAuthoritySource() != ContractCandidateAuthoritySource.SYSTEM_AUTO)
                || authority.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !authority.effectiveRoleSource().isAuthoritativeDecision()
                || authority.effectiveIdentityStatus() != EffectiveProjectIdentityStatus.SAME_PROJECT
                || !Objects.equals(candidate.sourceContractFileId(), authority.sourceFileId())
                || !Objects.equals(candidate.sourceParseResultId(), authority.parseResultId())
                || !Objects.equals(candidate.sourceRoleDecisionId(), authority.currentRoleDecisionId())
                || !Objects.equals(candidate.sourceIdentityDecisionId(), authority.currentIdentityDecisionId())
                || (candidate.sourceAuthoritySource() == ContractCandidateAuthoritySource.SYSTEM_AUTO)
                    != (authority.effectiveRoleSource() == EffectiveDocumentRoleSource.SYSTEM_AUTO_DECISION)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "candidate confirmation source authority is stale");
        }
    }

    private ContractCandidateConfirmationResult replay(IdempotencyRecord record,
            ConfirmContractCandidateCommand command, CurrentUser actor,
            ContractCandidateConfirmationPayloadCanonicalizer.Canonicalized canonical) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !command.candidateId().toString().equals(record.resourceId())
                || !"CONTRACT_CANDIDATE_CONFIRMATION".equals(record.resultResourceType())) {
            throw conflict();
        }
        UUID confirmationId;
        try { confirmationId = UUID.fromString(record.resultResourceId()); }
        catch (RuntimeException invalid) { throw conflict(); }
        var confirmation = confirmations.findByProjectAndCandidateAndId(command.projectId(),
                command.candidateId(), confirmationId).orElseThrow(
                        ContractCandidateConfirmationTransactionCoordinator::conflict);
        ConfirmedContractPayload persisted = canonicalizer.decodeAndVerify(confirmation);
        String fingerprint = commandFingerprint(actor, command, confirmation.sourceFileId(),
                confirmation.sourceParseResultId(), confirmation.sourceRoleDecisionId(),
                confirmation.sourceIdentityDecisionId(), confirmation.payloadFingerprint());
        if (!fingerprint.equals(record.requestFingerprint())
                || !canonical.json().equals(confirmation.canonicalPayloadJson())
                || confirmation.baseCandidateVersionNo() != command.expectedVersion()) throw conflict();
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            ContractCandidate candidate = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                    .orElseThrow(ContractCandidateConfirmationTransactionCoordinator::conflict);
            if (candidate.versionNo() < confirmation.resultCandidateVersionNo()) throw conflict();
            idempotency.markSucceeded(record, "CONTRACT_CANDIDATE_CONFIRMATION",
                    confirmation.confirmationId().toString(), clock.instant());
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) throw conflict();
        return new ContractCandidateConfirmationResult(confirmation, persisted, true);
    }

    private static String commandFingerprint(CurrentUser actor, ConfirmContractCandidateCommand command,
            UUID fileId, UUID parseId, UUID roleId, UUID identityId, String payloadFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {"contract-candidate-confirm-command-v1", actor.userId(),
                    command.projectId().toString(), command.candidateId().toString(),
                    Long.toString(command.expectedVersion()), fileId.toString(), parseId.toString(),
                    roleId.toString(), identityId.toString(), payloadFingerprint}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static OperationAuditEvent successAudit(ConfirmContractCandidateCommand command,
            CurrentUser actor, ContractCandidateConfirmation confirmation, Instant now) {
        return new OperationAuditEvent("PROJECT_CONTRACT_CANDIDATE_CONFIRMED", "CONTRACT", "confirm",
                actor.userId(), actor.username(), "CONTRACT_CANDIDATE", command.candidateId().toString(), null,
                OperationAuditResultStatus.SUCCESS, "SUCCESS", "confirm contract candidate",
                "{\"confirmationId\":\"" + confirmation.confirmationId() + "\",\"resultVersion\":"
                        + confirmation.resultCandidateVersionNo() + "}",
                safe(command.metadata().requestId()), safe(command.metadata().traceId()),
                command.metadata().sourceIp(), now);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "unavailable" : value; }
}
