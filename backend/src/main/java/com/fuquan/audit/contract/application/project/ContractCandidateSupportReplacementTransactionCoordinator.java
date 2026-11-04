package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementException.Classification.*;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy.FactSource;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContractCandidateSupportReplacementTransactionCoordinator {
    static final String SCOPE = "CONTRACT_CANDIDATE_SUPPORT_REPLACE";
    private static final String RESOURCE = "CONTRACT_CANDIDATE";
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ContractCandidateRepository candidates;
    private final CandidateSourceAuthorityValidator primaryAuthority;
    private final CandidateSupportAuthorityValidator supportAuthority;
    private final ExtractionResultRepository extractions;
    private final ContractCandidateSupportAuthorityRepository supports;
    private final ContractCandidateAssembler assembler;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;

    public ContractCandidateSupportReplacementTransactionCoordinator(ProjectRepository projects,
            ProjectFileRepository files, ContractCandidateRepository candidates,
            CandidateSourceAuthorityValidator primaryAuthority, CandidateSupportAuthorityValidator supportAuthority,
            ExtractionResultRepository extractions, ContractCandidateSupportAuthorityRepository supports,
            ContractCandidateAssembler assembler, IdempotencyPort idempotency, OperationAuditPort audit, Clock clock) {
        this.projects = projects;
        this.files = files;
        this.candidates = candidates;
        this.primaryAuthority = primaryAuthority;
        this.supportAuthority = supportAuthority;
        this.extractions = extractions;
        this.supports = supports;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** One short REQUIRED transaction; the monitor also serializes local adapter calls. */
    @Transactional
    public synchronized ContractCandidateSupportReplacementResult replace(
            ReplaceContractCandidateSupportAuthorityCommand command, CurrentUser actor) {
        try {
            return replaceInTransaction(command, actor);
        } catch (ContractCandidateSupportReplacementException classified) {
            throw classified;
        } catch (BusinessException failure) {
            throw new ContractCandidateSupportReplacementException(failure.errorCode(), failure.getMessage(),
                    OUTCOME_UNCERTAIN, failure);
        } catch (RuntimeException failure) {
            throw new ContractCandidateSupportReplacementException(BusinessErrorCode.INTERNAL_ERROR,
                    "candidate support replacement outcome cannot be proven", OUTCOME_UNCERTAIN, failure);
        }
    }

    private ContractCandidateSupportReplacementResult replaceInTransaction(
            ReplaceContractCandidateSupportAuthorityCommand command, CurrentUser actor) {
        String fingerprint = commandFingerprint(actor, command);
        var prior = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), command, actor, fingerprint, null);
        ContractCandidate discovered;
        try {
            discovered = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                    .orElseThrow(() -> missing("contract candidate not found"));
        } catch (BusinessException validationFailure) {
            return rejectOrReplay(validationFailure, command, actor, fingerprint, null);
        }
        List<UUID> fileIds = authorityFileIds(discovered, command);
        boolean[] entered = {false};
        try {
            return withFileFences(command.projectId(), fileIds, 0, () -> {
                entered[0] = true;
                return replaceLocked(command, actor, fingerprint, discovered, fileIds);
            });
        } catch (BusinessException failure) {
            // Only acquisition failures are deterministic rejections here; mutation failures stay uncertain.
            if (entered[0]) throw failure;
            BusinessException unavailable = failure.errorCode() == BusinessErrorCode.NOT_FOUND
                    ? missing("candidate authority file not found") : failure;
            return rejectOrReplay(unavailable, command, actor, fingerprint, null);
        }
    }

    private ContractCandidateSupportReplacementResult replaceLocked(
            ReplaceContractCandidateSupportAuthorityCommand command, CurrentUser actor, String fingerprint,
            ContractCandidate discovered, List<UUID> fileIds) {
        LockedCandidate locked = null;
        ResolvedCandidateSupportAuthoritySet resolved;
        try {
            locked = lockCandidate(command, discovered, fileIds);
            ContractCandidate candidate = locked.candidate();
            if (candidate.promotionState() != CandidatePromotionState.DRAFT
                    || candidate.versionNo() != command.expectedCandidateVersion()) {
                throw conflict("candidate support replacement version conflict");
            }
            requirePrimary(candidate, locked.primary());
            resolved = supportAuthority.resolveRequested(command.projectId(), canonicalIds(command));
        } catch (BusinessException validationFailure) {
            return rejectOrReplay(validationFailure, command, actor, fingerprint, locked);
        }
        ContractCandidate candidate = locked.candidate();
        long nextSupportVersion = candidate.supportAuthorityVersionNo() == null ? 1
                : Math.incrementExact(candidate.supportAuthorityVersionNo());
        List<ContractCandidateSupportAuthority> oldRows = supports.findCurrentByProjectIdAndCandidateId(
                command.projectId(), command.candidateId());
        Instant now = clock.instant();
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(), actor.userId(),
                RESOURCE, command.candidateId().toString(), fingerprint, RESOURCE, command.candidateId().toString(),
                command.metadata().requestId(), command.metadata().traceId(), now));
        if (!claim.acquired()) return replay(claim.record(), command, actor, fingerprint, locked);
        try {
            List<ContractCandidateSupportAuthority> newRows = resolved.supports().stream().map(source ->
                    new ContractCandidateSupportAuthority(UUID.randomUUID(), command.projectId(), command.candidateId(),
                            source.sourceFileId(), source.fileSha256(), source.parseResultId(), source.parseVersion(),
                            source.projectVersionNo(), source.effectiveRole(), source.roleDecisionId(), source.identityResolution(),
                            source.identityDecisionId(), source.sourceFactFingerprint(), nextSupportVersion, actor.userId(), now))
                    .toList();
            List<ExtractionResult> primaryFacts = exactFacts(command.projectId(), candidate.sourceContractFileId(),
                    candidate.sourceParseResultId(), candidate.id());
            List<FactSource> supportFacts = resolved.supports().stream().map(source -> new FactSource(
                    source.effectiveRole(), source.sourceFileId(), source.parseVersion(),
                    exactFacts(command.projectId(), source.sourceFileId(), source.parseResultId(), null))).toList();
            var derived = assembler.derive(candidate.id(), locked.primary(), primaryFacts, supportFacts, resolved.authorityMode());

            supports.deleteCurrentByProjectIdAndCandidateId(command.projectId(), command.candidateId());
            ContractCandidate updated = candidates.replaceDraftDerivedFactsIfVersionMatches(command.projectId(),
                    command.candidateId(), command.expectedCandidateVersion(), derived, nextSupportVersion,
                    resolved.supportAuthorityFingerprint(), now);
            if (updated.versionNo() != command.expectedCandidateVersion() + 1
                    || !Objects.equals(updated.supportAuthorityVersionNo(), nextSupportVersion)
                    || !resolved.supportAuthorityFingerprint().equals(updated.supportAuthorityFingerprint())) {
                throw conflict("candidate support replacement result cannot be proven");
            }
            if (!newRows.isEmpty()) supports.insertAll(newRows);
            audit.append(successAudit(command, actor, updated, oldRows.size(), newRows, now));
            idempotency.markSucceeded(claim.record(), RESOURCE, updated.id().toString(), now);
            return result(updated, newRows, false);
        } catch (RuntimeException failure) {
            try { idempotency.rollbackUncommittedClaim(claim.record()); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private record LockedCandidate(ContractCandidate candidate, ProjectFile primary) { }

    private ContractCandidateSupportReplacementResult rejectOrReplay(BusinessException validationFailure,
            ReplaceContractCandidateSupportAuthorityCommand command, CurrentUser actor, String fingerprint,
            LockedCandidate locked) {
        if (validationFailure.errorCode() == BusinessErrorCode.INTERNAL_ERROR
                || validationFailure.errorCode() == BusinessErrorCode.EXTERNAL_SERVICE_ERROR) throw validationFailure;
        // A committed same-key winner outranks even a deterministic failure after the absent read.
        var winner = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (winner.isPresent()) return replay(winner.get(), command, actor, fingerprint, locked);
        throw new ContractCandidateSupportReplacementException(validationFailure.errorCode(),
                validationFailure.getMessage(), REQUEST_REJECTED, validationFailure);
    }

    private <T> T withFileFences(UUID projectId, List<UUID> fileIds, int index, Supplier<T> operation) {
        if (index == fileIds.size()) return operation.get();
        return files.withFileAuthorityFence(projectId, fileIds.get(index),
                () -> withFileFences(projectId, fileIds, index + 1, operation));
    }

    private static List<UUID> authorityFileIds(ContractCandidate discovered,
            ReplaceContractCandidateSupportAuthorityCommand command) {
        return Stream.concat(Stream.of(discovered.sourceContractFileId()), command.supportFileIds().stream())
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private LockedCandidate lockCandidate(ReplaceContractCandidateSupportAuthorityCommand command) {
        ContractCandidate discovered = candidates.findByProjectIdAndId(command.projectId(), command.candidateId())
                .orElseThrow(() -> missing("contract candidate not found"));
        return lockCandidate(command, discovered, authorityFileIds(discovered, command));
    }

    private LockedCandidate lockCandidate(ReplaceContractCandidateSupportAuthorityCommand command,
            ContractCandidate discovered, List<UUID> fileIds) {
        ProjectFile primary = null;
        for (UUID fileId : fileIds) {
            ProjectFile locked = files.findByProjectIdAndIdForUpdate(command.projectId(), fileId)
                    .orElseThrow(() -> missing("candidate authority file not found"));
            if (fileId.equals(discovered.sourceContractFileId())) primary = locked;
        }
        projects.findByIdForUpdate(command.projectId()).orElseThrow(() -> missing("project not found"));
        ContractCandidate candidate = candidates.findByProjectIdAndIdForUpdate(command.projectId(), command.candidateId())
                .orElseThrow(() -> missing("contract candidate not found"));
        if (!candidate.sourceContractFileId().equals(discovered.sourceContractFileId())) {
            throw conflict("candidate primary source changed");
        }
        return new LockedCandidate(candidate, Objects.requireNonNull(primary));
    }

    private void requirePrimary(ContractCandidate candidate, ProjectFile file) {
        CandidateSourceAuthority authority = primaryAuthority.snapshot(candidate);
        authority.requirePromotable();
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || file.parsedText() == null || file.parsedText().isBlank()
                || (candidate.sourceAuthoritySource() != ContractCandidateAuthoritySource.HUMAN_CONFIRMATION
                    && candidate.sourceAuthoritySource() != ContractCandidateAuthoritySource.SYSTEM_AUTO)
                || authority.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !authority.effectiveRoleSource().isAuthoritativeDecision()
                || authority.effectiveIdentityStatus() != EffectiveProjectIdentityStatus.SAME_PROJECT
                || !Objects.equals(candidate.sourceContractFileId(), authority.sourceFileId())
                || !Objects.equals(candidate.sourceParseResultId(), authority.parseResultId())
                || !Objects.equals(candidate.sourceRoleDecisionId(), authority.currentRoleDecisionId())
                || !Objects.equals(candidate.sourceIdentityDecisionId(), authority.currentIdentityDecisionId())) {
            throw conflict("candidate primary authority is stale");
        }
    }

    private List<ExtractionResult> exactFacts(UUID projectId, UUID fileId, UUID parseId, UUID candidateId) {
        List<ExtractionResult> facts = extractions.findBySourceFileIdAndParseResultId(projectId, fileId, parseId);
        if (facts.stream().anyMatch(fact -> !projectId.equals(fact.projectId()) || !fileId.equals(fact.sourceFileId())
                || !parseId.equals(fact.parseResultId()) || !Objects.equals(candidateId, fact.candidateId()))) {
            throw conflict("candidate authority extraction binding mismatch");
        }
        return facts;
    }

    private ContractCandidateSupportReplacementResult replay(IdempotencyRecord record,
            ReplaceContractCandidateSupportAuthorityCommand command, CurrentUser actor, String fingerprint,
            LockedCandidate locked) {
        if (!SCOPE.equals(record.scope()) || !command.idempotencyKey().equals(record.idempotencyKey())
                || !actor.userId().equals(record.actorId()) || !RESOURCE.equals(record.resourceType())
                || !command.candidateId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint()) || !RESOURCE.equals(record.resultResourceType())
                || !command.candidateId().toString().equals(record.resultResourceId())) {
            throw integrity("idempotency key fingerprint conflict");
        }
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            throw uncertain("candidate support replacement is in progress");
        }
        if (record.status() != IdempotencyStatus.SUCCEEDED) {
            throw integrity("candidate support replacement durable claim has an invalid terminal state");
        }
        if (record.completedAt() == null || record.completedAt().isBefore(record.createdAt()) || record.versionNo() < 1) {
            throw integrity("candidate support replacement durable completion is inconsistent");
        }
        ContractCandidate candidate;
        try {
            // A claim loser must observe the winner's current result, not its earlier validation snapshot.
            candidate = locked == null ? lockCandidate(command).candidate()
                    : candidates.findByProjectIdAndIdForUpdate(command.projectId(), command.candidateId())
                            .orElseThrow(() -> missing("contract candidate not found"));
        } catch (BusinessException unavailable) {
            throw new ContractCandidateSupportReplacementException(unavailable.errorCode(), unavailable.getMessage(),
                    OUTCOME_UNCERTAIN, unavailable);
        }
        if (!candidate.id().equals(command.candidateId()) || !candidate.projectId().equals(command.projectId())) {
            throw integrity("candidate support replacement result ownership mismatch");
        }
        if (candidate.promotionState() == CandidatePromotionState.PROMOTED
                || candidate.versionNo() > command.expectedCandidateVersion() + 1) {
            throw new ContractCandidateSupportReplacementException(BusinessErrorCode.CONFLICT,
                    "candidate support replacement result was superseded", RESULT_SUPERSEDED);
        }
        if (candidate.versionNo() < command.expectedCandidateVersion() + 1) {
            throw uncertain("candidate support replacement result version cannot be proven");
        }
        if (candidate.supportAuthorityVersionNo() == null || candidate.supportAuthorityFingerprint() == null
                || candidate.supportAuthorityVersionNo() > candidate.versionNo()) {
            throw integrity("candidate support replacement result metadata is inconsistent");
        }
        List<ContractCandidateSupportAuthority> stored = supports.findCurrentByProjectIdAndCandidateId(
                command.projectId(), command.candidateId());
        if (stored == null) throw uncertain("candidate support replacement cohort is unavailable");
        if (stored.stream().anyMatch(Objects::isNull)) throw integrity("candidate support replacement cohort is corrupt");
        List<ContractCandidateSupportAuthority> rows = stored.stream()
                .sorted(Comparator.comparing(row -> row.sourceFileId().toString())).toList();
        if (!rows.stream().map(ContractCandidateSupportAuthority::sourceFileId).toList().equals(canonicalIds(command))
                || rows.stream().anyMatch(row -> !row.projectId().equals(candidate.projectId())
                    || !row.candidateId().equals(candidate.id())
                    || !row.createdBy().equals(record.actorId())
                    || row.authorityVersion() != candidate.supportAuthorityVersionNo())) {
            throw integrity("candidate support replacement cohort is inconsistent");
        }
        if (!storedAuthorityFingerprint(rows).equals(candidate.supportAuthorityFingerprint())) {
            throw integrity("candidate support replacement cohort fingerprint is inconsistent");
        }
        // Read only the stored result: never revalidate today's parse, role, identity or facts here.
        return result(candidate, rows, true);
    }

    /** Same versioned cohort encoding as fresh resolution, using only immutable stored source fingerprints. */
    private static String storedAuthorityFingerprint(List<ContractCandidateSupportAuthority> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addFingerprintFields(digest, "contract-candidate-support-authority-v1", mode(rows.size()), rows.size());
            if (rows.isEmpty()) addFingerprintFields(digest, "EMPTY");
            for (ContractCandidateSupportAuthority row : rows) {
                addFingerprintFields(digest, row.projectId(), row.sourceFileId(), row.fileSha256(), row.parseResultId(),
                        row.parseVersion(), row.projectVersionNo(), row.effectiveRole(), row.roleDecisionId(),
                        row.identityResolution(), row.identityDecisionId(), row.sourceFactFingerprint());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required by Java 17", failure); }
    }

    private static void addFingerprintFields(MessageDigest digest, Object... fields) {
        for (Object field : fields) {
            byte[] bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private static ContractCandidateSupportReplacementException integrity(String message) {
        return new ContractCandidateSupportReplacementException(BusinessErrorCode.CONFLICT, message, INTEGRITY_CONFLICT);
    }

    private static ContractCandidateSupportReplacementException uncertain(String message) {
        return new ContractCandidateSupportReplacementException(BusinessErrorCode.CONFLICT, message, OUTCOME_UNCERTAIN);
    }

    private static ContractCandidateSupportReplacementResult result(ContractCandidate candidate,
            List<ContractCandidateSupportAuthority> rows, boolean replayed) {
        return new ContractCandidateSupportReplacementResult(candidate.id(), candidate.versionNo(),
                candidate.supportAuthorityVersionNo(), mode(rows.size()), rows, replayed);
    }

    static String commandFingerprint(CurrentUser actor, ReplaceContractCandidateSupportAuthorityCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Stream.concat(Stream.of("contract-candidate-support-replace-v1", actor.userId(), command.projectId().toString(),
                    command.candidateId().toString(), Long.toString(command.expectedCandidateVersion())),
                    canonicalIds(command).stream().map(UUID::toString)).forEach(value -> {
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                        digest.update(bytes);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required by Java 17", failure); }
    }

    private static List<UUID> canonicalIds(ReplaceContractCandidateSupportAuthorityCommand command) {
        return command.supportFileIds().stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static CandidateAuthorityMode mode(int count) {
        return count == 0 ? CandidateAuthorityMode.PRIMARY_ONLY_COMPAT : CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY;
    }

    private static OperationAuditEvent successAudit(ReplaceContractCandidateSupportAuthorityCommand command,
            CurrentUser actor, ContractCandidate updated, int oldCount,
            List<ContractCandidateSupportAuthority> rows, Instant now) {
        String ids = rows.stream().map(row -> "\"" + row.sourceFileId() + "\"").collect(Collectors.joining(","));
        String detail = "{\"projectId\":\"" + command.projectId() + "\",\"candidateId\":\"" + command.candidateId()
                + "\",\"oldMode\":\"" + mode(oldCount) + "\",\"newMode\":\"" + mode(rows.size())
                + "\",\"oldSupportCount\":" + oldCount + ",\"newSupportCount\":" + rows.size()
                + ",\"supportFileIds\":[" + ids + "],\"candidateVersion\":" + updated.versionNo()
                + ",\"supportAuthorityVersion\":" + updated.supportAuthorityVersionNo()
                + ",\"supportAuthorityFingerprint\":\"" + updated.supportAuthorityFingerprint() + "\"}";
        return new OperationAuditEvent("PROJECT_CANDIDATE_SUPPORT_REPLACED", "CONTRACT", "replace-support",
                actor.userId(), actor.username(), RESOURCE, updated.id().toString(), null,
                OperationAuditResultStatus.SUCCESS, "SUCCESS", "replace candidate support authority", detail,
                command.metadata().requestId(), command.metadata().traceId(), command.metadata().sourceIp(), now);
    }

    private static BusinessException missing(String reason) { return new BusinessException(BusinessErrorCode.NOT_FOUND, reason); }
    private static BusinessException conflict(String reason) { return new BusinessException(BusinessErrorCode.CONFLICT, reason); }
}
