package com.fuquan.audit.contract.application.project.role;

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
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
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
public class RoleConfirmationTransactionCoordinator {
    public static final String SCOPE = "ROLE_CONFIRMATION";
    public static final String RESULT_RESOURCE = "PROJECT_FILE_ROLE_DECISION";
    private static final String RESOURCE = "PROJECT_FILE";
    private static final String FINGERPRINT_VERSION = "role-confirmation-fingerprint-v1";

    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleAssessmentRepository assessments;
    private final ProjectFileRoleDecisionRepository decisions;
    private final ContractCandidateRepository candidates;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;

    public RoleConfirmationTransactionCoordinator(ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileRoleAssessmentRepository assessments,
            ProjectFileRoleDecisionRepository decisions, ContractCandidateRepository candidates,
            IdempotencyPort idempotency, OperationAuditPort audit) {
        this.files = files;
        this.parses = parses;
        this.assessments = assessments;
        this.decisions = decisions;
        this.candidates = candidates;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleConfirmationResult confirm(RoleConfirmationCommand command, CurrentUser actor,
                                          UUID decisionId, Instant occurredAt) {
        Authority authority = authority(command);
        String fingerprint = fingerprint(command, actor, authority);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, command.idempotencyKey(), actor.userId(), RESOURCE, command.fileId().toString(),
                fingerprint, RESULT_RESOURCE, decisionId.toString(), command.metadata().requestId(),
                command.metadata().traceId(), occurredAt));
        if (!claim.acquired()) return replay(claim.record(), command, actor, authority, fingerprint, occurredAt);

        ProjectFileRoleDecision staged = null;
        boolean decisionPublished = false;
        try {
            requireNewDecisionAllowed(command);
            Optional<ProjectFileRoleDecision> latest = decisions.findLatestByFileId(command.fileId());
            long currentVersion = latest.map(ProjectFileRoleDecision::versionNo).orElse(0L);
            if (currentVersion != command.expectedVersion()) conflict("role decision version conflict");
            ProjectFileRoleDecision proposed = new ProjectFileRoleDecision(decisionId, command.projectId(),
                    command.fileId(), authority.file().sha256(), authority.parse().parseResultId(),
                    authority.parse().parseVersion(), command.assessmentId(), command.selectedRole(),
                    RoleDecisionStatus.CONFIRMED, actor.userId(), occurredAt,
                    latest.map(ProjectFileRoleDecision::decisionId).orElse(null), command.reason(),
                    command.expectedVersion() + 1);
            ProjectFileRoleDecision stored;
            try {
                stored = decisions.stageInsertIfAbsent(proposed);
            } catch (IllegalStateException streamConflict) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "role decision version conflict");
            }
            if (!stored.equals(proposed)) conflict("role decision version conflict");
            staged = stored;
            audit.append(event("PROJECT_FILE_ROLE_CONFIRMED", "roleConfirmation", actor, stored,
                    command, occurredAt, "human document role confirmed"));
            decisions.commitStagedInsert(stored);
            decisionPublished = true;
            idempotency.markSucceeded(claim.record(), RESULT_RESOURCE, stored.decisionId().toString(), occurredAt);
            return new RoleConfirmationResult(stored, false);
        } catch (RuntimeException failure) {
            if (!decisionPublished) {
                if (staged != null) compensateDecision(staged, failure);
                compensateClaim(claim.record(), failure);
            }
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleConfirmationResult reconcile(RoleConfirmationCommand command, CurrentUser actor,
                                            RuntimeException original, Instant occurredAt) {
        Authority authority = authority(command);
        String fingerprint = fingerprint(command, actor, authority);
        IdempotencyRecord record = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "role confirmation outcome cannot be proven", original));
        requireIdempotencyIdentity(record, command, actor, fingerprint);
        ProjectFileRoleDecision decision = decision(record, command, actor, authority);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            audit.append(event("PROJECT_FILE_ROLE_CONFIRMATION_RECONCILED", "roleConfirmationReconcile",
                    actor, decision, command, occurredAt, "confirmation facts proven after uncertain response"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, decision.decisionId().toString(), occurredAt);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) {
            conflict("role confirmation has inconsistent idempotency status");
        }
        return new RoleConfirmationResult(decision, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RoleConfirmationResult reopen(UUID projectId, UUID fileId, UUID decisionId, boolean replayed) {
        ProjectFileRoleDecision decision = decisions.findById(decisionId)
                .filter(value -> value.projectId().equals(projectId) && value.fileId().equals(fileId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "role decision not found"));
        return new RoleConfirmationResult(decision, replayed);
    }

    private RoleConfirmationResult replay(IdempotencyRecord record, RoleConfirmationCommand command,
            CurrentUser actor, Authority authority, String fingerprint, Instant occurredAt) {
        requireIdempotencyIdentity(record, command, actor, fingerprint);
        if (record.status() == IdempotencyStatus.SUCCEEDED) {
            return new RoleConfirmationResult(decision(record, command, actor, authority), true);
        }
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            UUID id = resourceId(record);
            Optional<ProjectFileRoleDecision> durable = decisions.findById(id);
            if (durable.isPresent()) {
                ProjectFileRoleDecision decision = requireDecision(durable.orElseThrow(), command, actor, authority);
                audit.append(event("PROJECT_FILE_ROLE_CONFIRMATION_RECONCILED", "roleConfirmationReconcile",
                        actor, decision, command, occurredAt, "confirmation facts proven during same-key replay"));
                idempotency.markSucceeded(record, RESULT_RESOURCE, decision.decisionId().toString(), occurredAt);
                return new RoleConfirmationResult(decision, true);
            }
            conflict("role confirmation is in progress or outcome is unknown");
        }
        conflict("role confirmation has inconsistent idempotency status");
        throw new IllegalStateException("unreachable");
    }

    private Authority authority(RoleConfirmationCommand command) {
        ProjectFile file = files.findByProjectIdAndIdForUpdate(command.projectId(), command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        ProjectFileParseResult parse = parses.findLatestByFileId(command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "immutable parse result is required"));
        ProjectFileRoleAssessment assessment = assessments.findById(command.assessmentId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "role assessment not found"));
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || parse.parseStatus() != ProjectFileParseStatus.PARSED
                || !parse.projectId().equals(file.projectId()) || !parse.fileId().equals(file.id())
                || !parse.fileSha256().equals(file.sha256())
                || !Objects.equals(parse.canonicalText(), file.parsedText())
                || assessment.executionStatus() != RoleAssessmentExecutionStatus.COMPLETED
                || !assessment.projectId().equals(file.projectId()) || !assessment.fileId().equals(file.id())
                || !assessment.fileSha256().equals(file.sha256())
                || !assessment.parseResultId().equals(parse.parseResultId())
                || assessment.parseVersion() != parse.parseVersion()) {
            conflict("role confirmation authoritative binding is inconsistent");
        }
        return new Authority(file, parse, assessment);
    }

    private void requireNewDecisionAllowed(RoleConfirmationCommand command) {
        if (command.selectedRole() != DocumentRole.CONTRACT_BODY) {
            candidates.findBySourceContractFileIdForUpdate(command.projectId(), command.fileId())
                    .filter(candidate -> candidate.promotionState() == CandidatePromotionState.PROMOTED)
                    .ifPresent(candidate -> conflict(
                            "promoted contract source role divergence requires a later explicit disposition workflow"));
        }
    }

    private ProjectFileRoleDecision decision(IdempotencyRecord record, RoleConfirmationCommand command,
            CurrentUser actor, Authority authority) {
        return requireDecision(decisions.findById(resourceId(record))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "role decision not found")), command, actor, authority);
    }

    private static ProjectFileRoleDecision requireDecision(ProjectFileRoleDecision decision,
            RoleConfirmationCommand command, CurrentUser actor, Authority authority) {
        if (!decision.projectId().equals(command.projectId()) || !decision.fileId().equals(command.fileId())
                || !decision.fileSha256().equals(authority.file().sha256())
                || !decision.parseResultId().equals(authority.parse().parseResultId())
                || decision.parseVersion() != authority.parse().parseVersion()
                || !decision.assessmentId().equals(command.assessmentId())
                || decision.selectedRole() != command.selectedRole()
                || !decision.decidedBy().equals(actor.userId())
                || decision.versionNo() != command.expectedVersion() + 1
                || !Objects.equals(decision.reason(), command.reason())) {
            conflict("idempotent role decision durable state is inconsistent");
        }
        return decision;
    }

    private static void requireIdempotencyIdentity(IdempotencyRecord record, RoleConfirmationCommand command,
            CurrentUser actor, String fingerprint) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType()) || !command.fileId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())
                || !RESULT_RESOURCE.equals(record.resultResourceType())) {
            conflict("idempotency key fingerprint conflict");
        }
    }

    static String fingerprint(RoleConfirmationCommand command, CurrentUser actor, Authority authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION);
            add(digest, actor.userId());
            add(digest, command.projectId().toString());
            add(digest, command.fileId().toString());
            add(digest, authority.file().sha256());
            add(digest, authority.parse().parseResultId().toString());
            add(digest, Integer.toString(authority.parse().parseVersion()));
            add(digest, command.assessmentId().toString());
            add(digest, command.selectedRole().name());
            add(digest, Long.toString(command.expectedVersion()));
            add(digest, command.reason());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static UUID resourceId(IdempotencyRecord record) {
        try {
            return UUID.fromString(record.resultResourceId());
        } catch (RuntimeException invalid) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "idempotency role decision identity is invalid");
        }
    }

    private static OperationAuditEvent event(String type, String action, CurrentUser actor,
            ProjectFileRoleDecision decision, RoleConfirmationCommand command, Instant occurredAt, String detail) {
        return new OperationAuditEvent(type, "CONTRACT", action, actor.userId(), actor.username(),
                RESULT_RESOURCE, decision.decisionId().toString(), null, OperationAuditResultStatus.SUCCESS,
                decision.selectedRole().name(), action, detail, command.metadata().requestId(),
                command.metadata().traceId(), command.metadata().sourceIp(), occurredAt);
    }

    private void compensateDecision(ProjectFileRoleDecision decision, RuntimeException original) {
        try {
            decisions.rollbackUncommittedInsert(decision);
        } catch (RuntimeException compensation) {
            original.addSuppressed(compensation);
        }
    }

    private void compensateClaim(IdempotencyRecord record, RuntimeException original) {
        try {
            idempotency.rollbackUncommittedClaim(record);
        } catch (RuntimeException compensation) {
            original.addSuppressed(compensation);
        }
    }

    private static void conflict(String message) {
        throw new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    record Authority(ProjectFile file, ProjectFileParseResult parse, ProjectFileRoleAssessment assessment) { }
}
