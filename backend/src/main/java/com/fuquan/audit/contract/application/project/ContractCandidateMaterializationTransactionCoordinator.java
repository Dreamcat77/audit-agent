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
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.application.project.AutomaticDecisionProvenance;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentity;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentityConflictException;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.Project;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContractCandidateMaterializationTransactionCoordinator {
    public static final String SCOPE = "CONTRACT_CANDIDATE_MATERIALIZE";
    static final String RESOURCE = "PROJECT_FILE_ROLE_DECISION";
    public static final String RESULT_RESOURCE = "CONTRACT_CANDIDATE";
    static final String FINGERPRINT_VERSION = "contract-candidate-materialize-v2";

    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleDecisionRepository decisions;
    private final ProjectFileIdentityDecisionRepository identityDecisions;
    private final ProjectRepository projects;
    private final EffectiveProjectIdentityResolver effectiveIdentity;
    private final EffectiveDocumentRoleResolver effectiveRoles;
    private final ExtractionResultRepository extractions;
    private final ContractCandidateRepository candidates;
    private final ContractCandidateAssembler assembler;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;

    public ContractCandidateMaterializationTransactionCoordinator(ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileRoleDecisionRepository decisions,
            ProjectFileIdentityDecisionRepository identityDecisions, ProjectRepository projects,
            EffectiveDocumentRoleResolver effectiveRoles, EffectiveProjectIdentityResolver effectiveIdentity,
            ExtractionResultRepository extractions,
            ContractCandidateRepository candidates, ContractCandidateAssembler assembler,
            IdempotencyPort idempotency, OperationAuditPort audit, Clock clock) {
        this.files = files;
        this.parses = parses;
        this.decisions = decisions;
        this.identityDecisions = identityDecisions;
        this.projects = projects;
        this.effectiveIdentity = effectiveIdentity;
        this.effectiveRoles = effectiveRoles;
        this.extractions = extractions;
        this.candidates = candidates;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized ContractCandidateMaterializationResult materialize(UUID projectId, UUID fileId,
            UUID roleDecisionId, UUID identityDecisionId, CurrentUser actor, ProjectRequestMetadata metadata,
            UUID proposedCandidateId, Instant occurredAt) {
        String operationKey = "role-decision:" + roleDecisionId + ":identity-decision:" + identityDecisionId;
        Authority authority = authority(projectId, fileId, roleDecisionId, identityDecisionId);
        String fingerprint = fingerprint(authority);
        Optional<IdempotencyRecord> completed = idempotency.findByScopeAndKey(SCOPE, operationKey);
        if (completed.isPresent() && completed.orElseThrow().status() == IdempotencyStatus.SUCCEEDED) {
            return replay(completed.orElseThrow(), authority, fingerprint, occurredAt);
        }
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, operationKey, authority.decision().decidedBy(), RESOURCE, roleDecisionId.toString(), fingerprint,
                RESULT_RESOURCE, proposedCandidateId.toString(), safe(metadata.requestId()),
                safe(metadata.traceId()), occurredAt));
        if (!claim.acquired()) return replay(claim.record(), authority, fingerprint, occurredAt);

        try {
            Optional<ContractCandidate> prior = candidates.findBySourceContractFileIdForUpdate(projectId, fileId);
            UUID targetId = prior.map(ContractCandidate::id).orElse(proposedCandidateId);
            if (prior.isPresent() && legacyReuse(prior.orElseThrow(), authority)) {
                ContractCandidate historical = prior.orElseThrow();
                if (historical.promotionState() != CandidatePromotionState.DRAFT)
                    conflict("promoted candidate provenance is immutable");
                ContractCandidate authorized = candidates.reauthorizeDraftIfVersionMatches(projectId,
                        historical.id(), historical.versionNo(), authority.parse().parseResultId(),
                        authority.decision().decisionId(), authority.identityDecision().decisionId(),
                        candidateAuthoritySource(authority),
                        fingerprint, clock.instant());
                audit.append(audit(actor, metadata, authorized, authority, occurredAt));
                idempotency.markSucceeded(claim.record(), RESULT_RESOURCE, authorized.id().toString(), occurredAt);
                return new ContractCandidateMaterializationResult(authorized,
                        ContractCandidateMaterializationStatus.REPLAYED);
            }
            ContractCandidate proposed = assembler.assemble(targetId, projectId, authority.file(),
                    authority.parse().parseResultId(), authority.decision().decisionId(),
                    authority.identityDecision().decisionId(), candidateAuthoritySource(authority), fingerprint,
                    authority.facts());
            prior.ifPresent(existing -> requireCompatible(existing, proposed, authority));
            validateBindings(authority.facts(), targetId);

            ContractCandidate stored;
            boolean created = prior.isEmpty();
            if (prior.isEmpty()) {
                stored = candidates.insertIfAbsent(proposed);
                created = stored.id().equals(proposedCandidateId);
            } else {
                ContractCandidate existing = prior.orElseThrow();
                if (existing.promotionState() == CandidatePromotionState.PROMOTED
                        || sameAuthority(existing, authority, fingerprint)) {
                    stored = existing;
                } else {
                    stored = candidates.reauthorizeDraftIfVersionMatches(projectId, existing.id(),
                            existing.versionNo(), authority.parse().parseResultId(),
                            authority.decision().decisionId(), authority.identityDecision().decisionId(),
                            candidateAuthoritySource(authority),
                            fingerprint, clock.instant());
                }
            }
            for (ExtractionResult fact : authority.facts()) {
                extractions.bindCandidateIfUnbound(projectId, fileId, authority.parse().parseResultId(),
                        fact.extractionKey(), stored.id());
            }
            audit.append(audit(actor, metadata, stored, authority, occurredAt));
            idempotency.markSucceeded(claim.record(), RESULT_RESOURCE, stored.id().toString(), occurredAt);
            return new ContractCandidateMaterializationResult(stored,
                    created ? ContractCandidateMaterializationStatus.MATERIALIZED
                            : ContractCandidateMaterializationStatus.REPLAYED);
        } catch (RuntimeException failure) {
            try {
                idempotency.rollbackUncommittedClaim(claim.record());
            } catch (RuntimeException compensation) {
                failure.addSuppressed(compensation);
            }
            throw failure;
        }
    }

    private ContractCandidateMaterializationResult replay(IdempotencyRecord record, Authority authority,
            String fingerprint, Instant occurredAt) {
        if (!SCOPE.equals(record.scope()) || !authority.decision().decidedBy().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType())
                || !authority.decision().decisionId().toString().equals(record.resourceId())
                || !record.idempotencyKey().equals("role-decision:" + authority.decision().decisionId()
                    + ":identity-decision:" + authority.identityDecision().decisionId())
                || !fingerprint.equals(record.requestFingerprint())
                || !RESULT_RESOURCE.equals(record.resultResourceType())) {
            conflict("candidate materialization idempotency fingerprint conflict");
        }
        ContractCandidate candidate = candidate(record, authority);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            requireAllBound(authority, candidate);
            idempotency.markSucceeded(record, RESULT_RESOURCE, candidate.id().toString(), occurredAt);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) {
            conflict("candidate materialization durable state is inconsistent");
        }
        return new ContractCandidateMaterializationResult(candidate,
                ContractCandidateMaterializationStatus.REPLAYED);
    }

    private ContractCandidate candidate(IdempotencyRecord record, Authority authority) {
        Optional<ContractCandidate> byResult;
        try {
            byResult = candidates.findByProjectIdAndId(authority.file().projectId(),
                    UUID.fromString(record.resultResourceId()));
        } catch (RuntimeException invalid) {
            byResult = Optional.empty();
        }
        ContractCandidate candidate = byResult.or(() -> candidates.findBySourceContractFileId(
                        authority.file().projectId(), authority.file().id()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "materialized candidate durable state is missing"));
        boolean historicalNoParseBoundFacts = authority.parse().parseOrigin()
                == com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin.LEGACY_IMPORTED
                && authority.facts().isEmpty();
        if (!sameAuthority(candidate, authority, record.requestFingerprint())
                || (!historicalNoParseBoundFacts && !ProjectFactIdentity.sameCandidateContent(candidate,
                assembler.assemble(candidate.id(), candidate.projectId(), authority.file(),
                        authority.parse().parseResultId(), authority.decision().decisionId(),
                        authority.identityDecision().decisionId(),
                        candidateAuthoritySource(authority),
                        record.requestFingerprint(), authority.facts())))) {
            conflict("materialized candidate durable state is inconsistent");
        }
        return candidate;
    }

    private Authority authority(UUID projectId, UUID fileId, UUID roleDecisionId,
            UUID identityDecisionId) {
        ProjectFile file = files.findByProjectIdAndIdForUpdate(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "project file not found"));
        ProjectFileParseResult parse = parses.findLatestByFileId(fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "immutable parse result is required"));
        if (parse.parseStatus() != ProjectFileParseStatus.PARSED || parse.canonicalText() == null
                || parse.canonicalText().isBlank()) {
            conflict("a successfully parsed source is required for candidate materialization");
        }
        ProjectFileRoleDecision decision = decisions.findById(roleDecisionId)
                .filter(value -> value.projectId().equals(projectId) && value.fileId().equals(fileId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "role decision not found"));
        ProjectFileRoleDecision latest = decisions.findLatestByFileId(fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "authoritative role decision is required"));
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ProjectFileIdentityDecision identityDecision = identityDecisions.findById(identityDecisionId)
                .filter(value -> value.projectId().equals(projectId) && value.fileId().equals(fileId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "identity decision not found"));
        var effective = effectiveRoles.resolve(file);
        var projectIdentity = effectiveIdentity.resolve(project, file);
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || parse.parseStatus() != ProjectFileParseStatus.PARSED
                || !parse.projectId().equals(projectId) || !parse.fileId().equals(fileId)
                || !parse.fileSha256().equals(file.sha256())
                || !Objects.equals(parse.canonicalText(), file.parsedText())
                || !latest.decisionId().equals(roleDecisionId)
                || decision.selectedRole() != DocumentRole.CONTRACT_BODY
                || effective.effectiveRole() != DocumentRole.CONTRACT_BODY
                || !effective.source().isAuthoritativeDecision()
                || !roleDecisionId.equals(effective.roleDecisionId())
                || !decision.fileSha256().equals(file.sha256())
                || !decision.parseResultId().equals(parse.parseResultId())
                || decision.parseVersion() != parse.parseVersion()
                || !projectIdentity.eligibleForProjectUse()
                || !identityDecisionId.equals(projectIdentity.currentHumanSameDecision().decisionId())
                || identityDecision.projectVersionNo() != project.versionNo()
                || !identityDecision.fileSha256().equals(file.sha256())
                || !identityDecision.parseResultId().equals(parse.parseResultId())
                || identityDecision.parseVersion() != parse.parseVersion()) {
            conflict("candidate source role is not authoritative");
        }
        List<ExtractionResult> facts = extractions.findBySourceFileIdAndParseResultId(
                projectId, fileId, parse.parseResultId()).stream()
                .sorted(Comparator.comparing(ExtractionResult::extractionKey)).toList();
        if (facts.stream().anyMatch(fact -> !fact.projectId().equals(projectId)
                || !fact.sourceFileId().equals(fileId)
                || !parse.parseResultId().equals(fact.parseResultId()))) {
            conflict("candidate extraction parse binding is inconsistent");
        }
        return new Authority(file, parse, decision, identityDecision, facts);
    }

    private static boolean legacyReuse(ContractCandidate existing, Authority authority) {
        return authority.parse().parseOrigin()
                == com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin.LEGACY_IMPORTED
                && (existing.sourceParseResultId() == null
                    || existing.sourceParseResultId().equals(authority.parse().parseResultId()))
                && authority.facts().isEmpty();
    }

    private static void requireCompatible(ContractCandidate existing, ContractCandidate proposed,
            Authority authority) {
        if (!ProjectFactIdentity.sameCandidateContent(existing, proposed)) {
            conflict("existing candidate facts conflict with authoritative extraction");
        }
        if (existing.sourceParseResultId() != null
                && !existing.sourceParseResultId().equals(authority.parse().parseResultId())) {
            conflict("existing candidate parse authority conflict");
        }
    }

    private static boolean sameAuthority(ContractCandidate candidate, Authority authority,
            String fingerprint) {
        return candidate.sourceAuthoritySource() == candidateAuthoritySource(authority)
                && authority.parse().parseResultId().equals(candidate.sourceParseResultId())
                && authority.decision().decisionId().equals(candidate.sourceRoleDecisionId())
                && authority.identityDecision().decisionId().equals(candidate.sourceIdentityDecisionId())
                && fingerprint.equals(candidate.sourceFactFingerprint());
    }

    private static ContractCandidateAuthoritySource candidateAuthoritySource(Authority authority) {
        return AutomaticDecisionProvenance.isAutomatic(authority.decision().reason())
                && AutomaticDecisionProvenance.isAutomatic(authority.identityDecision().reason())
                ? ContractCandidateAuthoritySource.SYSTEM_AUTO
                : ContractCandidateAuthoritySource.HUMAN_CONFIRMATION;
    }

    private static void validateBindings(List<ExtractionResult> facts, UUID candidateId) {
        if (facts.stream().anyMatch(fact -> fact.candidateId() != null
                && !candidateId.equals(fact.candidateId()))) {
            throw ProjectFactIdentityConflictException.extractionCandidateBinding();
        }
    }

    private void requireAllBound(Authority authority, ContractCandidate candidate) {
        List<ExtractionResult> stored = extractions.findBySourceFileIdAndParseResultId(
                authority.file().projectId(), authority.file().id(), authority.parse().parseResultId());
        if (stored.size() != authority.facts().size()
                || stored.stream().anyMatch(fact -> !candidate.id().equals(fact.candidateId()))) {
            conflict("candidate evidence binding is incomplete");
        }
    }

    static String fingerprint(Authority authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION);
            add(digest, authority.file().projectId().toString());
            add(digest, authority.file().id().toString());
            add(digest, authority.file().sha256());
            add(digest, authority.parse().parseResultId().toString());
            add(digest, authority.parse().canonicalTextSha256());
            add(digest, authority.decision().decisionId().toString());
            add(digest, Long.toString(authority.identityDecision().projectVersionNo()));
            add(digest, authority.identityDecision().decisionId().toString());
            for (ExtractionResult fact : authority.facts()) {
                add(digest, fact.extractionVersion());
                add(digest, fact.extractionKey());
            }
            add(digest, "fact-count:" + authority.facts().size());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static OperationAuditEvent audit(CurrentUser actor, ProjectRequestMetadata metadata,
            ContractCandidate candidate, Authority authority, Instant occurredAt) {
        return new OperationAuditEvent("CONTRACT_CANDIDATE_MATERIALIZED", "CONTRACT", "materialize",
                actor.userId(), actor.username(), RESULT_RESOURCE, candidate.id().toString(), null,
                OperationAuditResultStatus.SUCCESS, "SUCCESS", "materialize contract candidate",
                "{\"fileId\":\"" + authority.file().id() + "\",\"parseResultId\":\""
                        + authority.parse().parseResultId() + "\",\"roleDecisionId\":\""
                        + authority.decision().decisionId() + "\",\"identityDecisionId\":\""
                        + authority.identityDecision().decisionId() + "\"}",
                safe(metadata.requestId()), safe(metadata.traceId()), metadata.sourceIp(), occurredAt);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static void conflict(String message) {
        throw new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    record Authority(ProjectFile file, ProjectFileParseResult parse,
                     ProjectFileRoleDecision decision, ProjectFileIdentityDecision identityDecision,
                     List<ExtractionResult> facts) {
        Authority {
            facts = List.copyOf(facts);
        }
    }
}
