package com.fuquan.audit.contract.application.project.identity;

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
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityConfirmationTransactionCoordinator {
    public static final String SCOPE = "PROJECT_IDENTITY_CONFIRMATION";
    public static final String RESULT_RESOURCE = "PROJECT_FILE_IDENTITY_DECISION";
    private static final String RESOURCE = "PROJECT_FILE";
    private static final String FINGERPRINT_VERSION = "project-identity-confirmation-fingerprint-v1";
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileIdentityAssessmentRepository assessments;
    private final ProjectFileIdentityDecisionRepository decisions;
    private final ContractCandidateRepository candidates;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;

    public IdentityConfirmationTransactionCoordinator(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileIdentityAssessmentRepository assessments,
            ProjectFileIdentityDecisionRepository decisions, ContractCandidateRepository candidates,
            IdempotencyPort idempotency, OperationAuditPort audit) {
        this.projects = projects; this.files = files; this.parses = parses; this.assessments = assessments;
        this.decisions = decisions; this.candidates = candidates; this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdentityConfirmationResult confirm(IdentityConfirmationCommand command, CurrentUser actor,
            UUID decisionId, Instant at) {
        Authority authority = authority(command);
        String fingerprint = fingerprint(command, actor, authority);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(),
                actor.userId(), RESOURCE, command.fileId().toString(), fingerprint, RESULT_RESOURCE,
                decisionId.toString(), command.metadata().requestId(), command.metadata().traceId(), at));
        if (!claim.acquired()) return replay(claim.record(), command, actor, authority, fingerprint, at);
        ProjectFileIdentityDecision staged = null;
        boolean published = false;
        try {
            if (command.resolution() == ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT)
                candidates.findBySourceContractFileIdForUpdate(command.projectId(), command.fileId())
                        .filter(value -> value.promotionState() == CandidatePromotionState.PROMOTED)
                        .ifPresent(value -> conflict("promoted contract project identity divergence requires a later explicit disposition workflow"));
            Optional<ProjectFileIdentityDecision> latest = decisions.findLatestByFileId(command.fileId());
            long version = latest.map(ProjectFileIdentityDecision::versionNo).orElse(0L);
            if (version != command.expectedVersion()) conflict("identity decision version conflict");
            ProjectFileIdentityDecision proposed = new ProjectFileIdentityDecision(decisionId,
                    command.projectId(), authority.project().versionNo(), command.fileId(),
                    authority.file().sha256(), authority.parse().parseResultId(),
                    authority.parse().parseVersion(), command.assessmentId(), command.resolution(),
                    actor.userId(), at, latest.map(ProjectFileIdentityDecision::decisionId).orElse(null),
                    command.reason(), command.expectedVersion() + 1);
            try { staged = decisions.stageInsertIfAbsent(proposed); }
            catch (IllegalStateException streamConflict) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "identity decision version conflict");
            }
            if (!staged.equals(proposed)) conflict("identity decision version conflict");
            audit.append(event(actor, staged, command, at, "PROJECT_FILE_IDENTITY_CONFIRMED"));
            decisions.commitStagedInsert(staged);
            published = true;
            idempotency.markSucceeded(claim.record(), RESULT_RESOURCE, staged.decisionId().toString(), at);
            return new IdentityConfirmationResult(staged, false);
        } catch (RuntimeException failure) {
            if (!published) {
                if (staged != null) compensateDecision(staged, failure);
                compensateClaim(claim.record(), failure);
            }
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdentityConfirmationResult reconcile(IdentityConfirmationCommand command, CurrentUser actor,
            RuntimeException original, Instant at) {
        Authority authority = authority(command);
        String fingerprint = fingerprint(command, actor, authority);
        var record = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("identity decision outcome cannot be proven", original));
        requireRecord(record, command, actor, fingerprint);
        var stored = decision(record, command, actor, authority);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            audit.append(event(actor, stored, command, at, "PROJECT_FILE_IDENTITY_CONFIRMATION_RECONCILED"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, stored.decisionId().toString(), at);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) conflict("identity decision durable status conflict");
        return new IdentityConfirmationResult(stored, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdentityConfirmationResult reopen(UUID projectId, UUID fileId, UUID decisionId, boolean replayed) {
        var decision = decisions.findById(decisionId)
                .filter(value -> value.projectId().equals(projectId) && value.fileId().equals(fileId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "identity decision not found"));
        return new IdentityConfirmationResult(decision, replayed);
    }
    private IdentityConfirmationResult replay(IdempotencyRecord record, IdentityConfirmationCommand command,
            CurrentUser actor, Authority authority, String fingerprint, Instant at) {
        requireRecord(record, command, actor, fingerprint);
        var stored = decision(record, command, actor, authority);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            audit.append(event(actor, stored, command, at, "PROJECT_FILE_IDENTITY_CONFIRMATION_RECONCILED"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, stored.decisionId().toString(), at);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) conflict("identity decision durable status conflict");
        return new IdentityConfirmationResult(stored, true);
    }
    private ProjectFileIdentityDecision decision(IdempotencyRecord record,
            IdentityConfirmationCommand command, CurrentUser actor, Authority authority) {
        UUID id;
        try { id = UUID.fromString(record.resultResourceId()); }
        catch (RuntimeException invalid) { throw new BusinessException(BusinessErrorCode.CONFLICT,
                "identity decision durable ID invalid"); }
        var value = decisions.findById(id)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "identity decision durable state is unproven"));
        if (!value.projectId().equals(command.projectId()) || !value.fileId().equals(command.fileId())
                || value.projectVersionNo() != authority.project().versionNo()
                || !value.fileSha256().equals(authority.file().sha256())
                || !value.parseResultId().equals(authority.parse().parseResultId())
                || value.parseVersion() != authority.parse().parseVersion()
                || !value.assessmentId().equals(command.assessmentId())
                || value.resolution() != command.resolution()
                || !value.decidedBy().equals(actor.userId())
                || value.versionNo() != command.expectedVersion() + 1
                || !Objects.equals(value.reason(), command.reason()))
            conflict("identity decision durable input drift");
        return value;
    }
    private Authority authority(IdentityConfirmationCommand command) {
        ProjectFile file = files.findByProjectIdAndIdForUpdate(command.projectId(), command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        Project project = projects.findByIdForUpdate(command.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ProjectFileParseResult parse = parses.findLatestByFileId(command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "immutable parse result is required"));
        ProjectFileIdentityAssessment assessment = assessments.findById(command.assessmentId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "identity assessment not found"));
        if (!project.metadataConfirmed() || file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || parse.parseStatus() != ProjectFileParseStatus.PARSED
                || !parse.projectId().equals(project.id()) || !parse.fileId().equals(file.id())
                || !parse.fileSha256().equals(file.sha256())
                || !Objects.equals(parse.canonicalText(), file.parsedText())
                || !assessment.projectId().equals(project.id())
                || assessment.projectVersionNo() != project.versionNo()
                || !assessment.fileId().equals(file.id())
                || !assessment.fileSha256().equals(file.sha256())
                || !assessment.parseResultId().equals(parse.parseResultId())
                || assessment.parseVersion() != parse.parseVersion())
            conflict("identity confirmation authoritative binding is inconsistent");
        return new Authority(project, file, parse, assessment);
    }
    static String fingerprint(IdentityConfirmationCommand command, CurrentUser actor, Authority authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION); add(digest, actor.userId());
            add(digest, command.projectId().toString());
            add(digest, Long.toString(authority.project().versionNo()));
            add(digest, command.fileId().toString()); add(digest, authority.file().sha256());
            add(digest, authority.parse().parseResultId().toString());
            add(digest, Integer.toString(authority.parse().parseVersion()));
            add(digest, command.assessmentId().toString()); add(digest, command.resolution().name());
            add(digest, Long.toString(command.expectedVersion())); add(digest, command.reason());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 required", failure); }
    }
    private static void add(MessageDigest digest, String value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static void requireRecord(IdempotencyRecord record, IdentityConfirmationCommand command,
            CurrentUser actor, String fingerprint) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType()) || !command.fileId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())
                || !RESULT_RESOURCE.equals(record.resultResourceType()))
            conflict("identity confirmation idempotency fingerprint conflict");
    }
    private static OperationAuditEvent event(CurrentUser actor, ProjectFileIdentityDecision decision,
            IdentityConfirmationCommand command, Instant at, String type) {
        return new OperationAuditEvent(type, "CONTRACT", "identityConfirmation", actor.userId(), actor.username(),
                RESULT_RESOURCE, decision.decisionId().toString(), null, OperationAuditResultStatus.SUCCESS,
                decision.resolution().name(), "confirm project file identity", "human identity resolution",
                command.metadata().requestId(), command.metadata().traceId(), command.metadata().sourceIp(), at);
    }
    private void compensateDecision(ProjectFileIdentityDecision value, RuntimeException failure) {
        try { decisions.rollbackUncommittedInsert(value); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
    }
    private void compensateClaim(IdempotencyRecord value, RuntimeException failure) {
        try { idempotency.rollbackUncommittedClaim(value); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
    }
    private static void conflict(String message) { throw new BusinessException(BusinessErrorCode.CONFLICT, message); }
    record Authority(Project project, ProjectFile file, ProjectFileParseResult parse,
            ProjectFileIdentityAssessment assessment) { }
}
