package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProjectFileProgressionRecoveryTransactionCoordinator {
    public static final String SCOPE = "PROJECT_FILE_PROGRESSION_RESUME";
    public static final String STAGE_SCOPE = "PROJECT_FILE_PROGRESSION_STAGE";
    private static final String RESOURCE = "PROJECT_FILE";
    private static final String FINGERPRINT_VERSION = "project-file-progression-resume-v1";

    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;

    public ProjectFileProgressionRecoveryTransactionCoordinator(IdempotencyPort idempotency,
            OperationAuditPort audit, Clock clock) {
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor) {
        Optional<IdempotencyRecord> existing = idempotency.findByScopeAndKey(
                SCOPE, durableKey(actor.userId(), command.idempotencyKey()));
        existing.ifPresent(record -> validate(record, command, actor));
        return existing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Start claim(ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        String key = durableKey(actor.userId(), command.idempotencyKey());
        String fingerprint = requestFingerprint(command, actor);
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, key, actor.userId(),
                RESOURCE, command.fileId().toString(), fingerprint,
                RESOURCE, command.fileId().toString(), safe(command.metadata().requestId()),
                safe(command.metadata().traceId()), clock.instant()));
        validate(claim.record(), command, actor);
        return new Start(claim.record(), claim.acquired());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Start claimStage(ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        String key = stageKey(command, actor);
        var claim = idempotency.claim(new IdempotencyRequest(STAGE_SCOPE, key, actor.userId(),
                RESOURCE, command.fileId().toString(), command.expectedRecoveryInputFingerprint(),
                "PROJECT_FILE_PROGRESSION_STAGE", command.expectedRecoveryInputFingerprint(),
                safe(command.metadata().requestId()), safe(command.metadata().traceId()), clock.instant()));
        validateStage(claim.record(), command, actor);
        return new Start(claim.record(), claim.acquired());
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findStage(ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor) {
        Optional<IdempotencyRecord> value = idempotency.findByScopeAndKey(
                STAGE_SCOPE, stageKey(command, actor));
        value.ifPresent(record -> validateStage(record, command, actor));
        return value;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord completeStage(IdempotencyRecord expected,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor, StageResult result) {
        IdempotencyRecord current = idempotency.findByScopeAndKey(expected.scope(), expected.idempotencyKey())
                .orElseThrow(() -> error("durable recovery stage identity is missing",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
        validateStage(current, command, actor);
        if (current.status() == IdempotencyStatus.SUCCEEDED) {
            requireResult(current, result);
            return current;
        }
        if (current.status() != IdempotencyStatus.IN_PROGRESS) {
            throw error("progression recovery stage was deterministically rejected",
                    ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        }
        return idempotency.markSucceeded(current, result.resultResourceType(),
                result.resultResourceId().toString(), clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(IdempotencyRecord expected, ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor) {
        IdempotencyRecord current = idempotency.findByScopeAndKey(expected.scope(), expected.idempotencyKey())
                .orElseThrow(() -> error("durable recovery identity is missing",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
        if (SCOPE.equals(current.scope())) validate(current, command, actor);
        else validateStage(current, command, actor);
        if (current.status() == IdempotencyStatus.IN_PROGRESS) {
            idempotency.markFailed(current, clock.instant());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord complete(IdempotencyRecord expected,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor,
            StageResult result) {
        IdempotencyRecord current = idempotency.findByScopeAndKey(expected.scope(), expected.idempotencyKey())
                .orElseThrow(() -> error("durable recovery identity is missing",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
        validate(current, command, actor);
        if (current.status() == IdempotencyStatus.SUCCEEDED) {
            requireResult(current, result);
            return current;
        }
        if (current.status() != IdempotencyStatus.IN_PROGRESS) {
            throw error("progression recovery was deterministically rejected",
                    ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        }
        audit.append(new OperationAuditEvent("PROJECT_FILE_PROGRESSION_RESUMED", "CONTRACT",
                "resumeProgression", actor.userId(), actor.username(), RESOURCE,
                command.fileId().toString(), null, OperationAuditResultStatus.SUCCESS,
                result.resultResourceType(), "resume project file progression",
                "{\"action\":\"" + actionFor(command.expectedBlocker()) + "\",\"resultType\":\""
                        + result.resultResourceType() + "\"}",
                safe(command.metadata().requestId()), safe(command.metadata().traceId()),
                command.metadata().sourceIp(), clock.instant()));
        return idempotency.markSucceeded(current, result.resultResourceType(),
                result.resultResourceId().toString(), clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findInner(String scope, String key) {
        return idempotency.findByScopeAndKey(scope, key);
    }

    static String requestFingerprint(ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        return digest(FINGERPRINT_VERSION, actor.userId(), command.projectId().toString(),
                command.fileId().toString(), command.expectedBlocker(),
                command.expectedRecoveryInputFingerprint());
    }

    static String durableKey(String actorId, String clientKey) {
        return "u" + digest("project-file-progression-resume-key-v1", actorId, clientKey);
    }

    static String innerKey(String stage, ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor) {
        return "progression-resume-" + stage + "-" + digest(
                "project-file-progression-inner-key-v1", actor.userId(), command.projectId().toString(),
                command.fileId().toString(), command.idempotencyKey(),
                command.expectedRecoveryInputFingerprint());
    }

    static String stageKey(ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        return "stage-" + digest("project-file-progression-stage-key-v1", actor.userId(),
                command.projectId().toString(), command.fileId().toString(), command.idempotencyKey(),
                command.expectedRecoveryInputFingerprint(), command.expectedBlocker());
    }

    static String actionFor(String blocker) {
        return switch (blocker) {
            case "ROLE_ASSESSMENT_PENDING" -> "RESUME_ROLE_ASSESSMENT";
            case "IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE" ->
                    "RESUME_IDENTITY_ASSESSMENT";
            case "CANDIDATE_MATERIALIZATION_PENDING" -> "RESUME_CANDIDATE_MATERIALIZATION";
            case "FACT_EXTRACTION_PENDING" -> "RESUME_FACT_EXTRACTION";
            default -> throw error("blocker is not machine recoverable",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_ACTION_NOT_ALLOWED);
        };
    }

    private static void validate(IdempotencyRecord record,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType())
                || !command.fileId().toString().equals(record.resourceId())
                || !durableKey(actor.userId(), command.idempotencyKey()).equals(record.idempotencyKey())
                || !requestFingerprint(command, actor).equals(record.requestFingerprint())) {
            throw error("idempotency request identity does not match the original command",
                    ProjectFileProgressionRecoveryException.Classification.IDEMPOTENCY_REQUEST_MISMATCH);
        }
    }

    private static void validateStage(IdempotencyRecord record,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        if (!STAGE_SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType())
                || !command.fileId().toString().equals(record.resourceId())
                || !stageKey(command, actor).equals(record.idempotencyKey())
                || !command.expectedRecoveryInputFingerprint().equals(record.requestFingerprint())) {
            throw error("recovery stage identity does not match the original command",
                    ProjectFileProgressionRecoveryException.Classification.IDEMPOTENCY_REQUEST_MISMATCH);
        }
    }

    private static void requireResult(IdempotencyRecord record, StageResult result) {
        if (!result.resultResourceType().equals(record.resultResourceType())
                || !result.resultResourceId().toString().equals(record.resultResourceId())) {
            throw error("completed progression recovery result identity is inconsistent",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static ProjectFileProgressionRecoveryException error(String message,
            ProjectFileProgressionRecoveryException.Classification classification) {
        return new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                message, classification);
    }

    public record Start(IdempotencyRecord record, boolean acquired) { }

    public record StageResult(String resultResourceType, UUID resultResourceId) {
        public StageResult {
            if (resultResourceType == null || resultResourceType.isBlank() || resultResourceId == null) {
                throw new IllegalArgumentException("stage result identity is required");
            }
        }
    }
}
