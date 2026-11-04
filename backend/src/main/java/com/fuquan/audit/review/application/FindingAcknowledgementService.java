package com.fuquan.audit.review.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.application.port.FindingAcknowledgementReadRepository;
import com.fuquan.audit.review.application.port.FindingAcknowledgementRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates acknowledgement validation around one separate short write transaction. */
@Service
public class FindingAcknowledgementService {

    public static final String IDEMPOTENCY_SCOPE = "REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE";

    private final AuditCheckResultRepository checkResultRepository;
    private final AuditTaskRepository auditTaskRepository;
    private final FindingAcknowledgementRepository acknowledgementRepository;
    private final FindingAcknowledgementReadRepository acknowledgementReadRepository;
    private final UserIdentityQueryPort userIdentityQueryPort;
    private final FindingAcknowledgementTransactionCoordinator transactionCoordinator;
    private final Clock clock;
    private final FlowDebugLogger flowDebugLogger;

    public FindingAcknowledgementService(
            AuditCheckResultRepository checkResultRepository,
            AuditTaskRepository auditTaskRepository,
            FindingAcknowledgementRepository acknowledgementRepository,
            FindingAcknowledgementReadRepository acknowledgementReadRepository,
            UserIdentityQueryPort userIdentityQueryPort,
            FindingAcknowledgementTransactionCoordinator transactionCoordinator,
            Clock clock,
            FlowDebugLogger flowDebugLogger) {
        this.checkResultRepository = Objects.requireNonNull(
                checkResultRepository, "checkResultRepository must not be null");
        this.auditTaskRepository = Objects.requireNonNull(
                auditTaskRepository, "auditTaskRepository must not be null");
        this.acknowledgementRepository = Objects.requireNonNull(
                acknowledgementRepository, "acknowledgementRepository must not be null");
        this.acknowledgementReadRepository = Objects.requireNonNull(
                acknowledgementReadRepository, "acknowledgementReadRepository must not be null");
        this.userIdentityQueryPort = Objects.requireNonNull(
                userIdentityQueryPort, "userIdentityQueryPort must not be null");
        this.transactionCoordinator = Objects.requireNonNull(
                transactionCoordinator, "transactionCoordinator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.flowDebugLogger = Objects.requireNonNull(flowDebugLogger, "flowDebugLogger must not be null");
    }

    /** Creates an acknowledgement or replays the original durable fact. */
    public FindingAcknowledgementView acknowledge(
            AcknowledgeFindingCommand command,
            CurrentUser currentUser) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        if (currentUser.userId().isBlank()) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "acknowledgement actor must not be blank");
        }

        AuditCheckResult result = checkResultRepository.findById(command.checkResultId())
                .filter(found -> found.id().equals(command.checkResultId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        AuditTask task = auditTaskRepository.findById(result.taskId())
                .filter(found -> found.id().equals(result.taskId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        validateEligible(result);

        UUID acknowledgementId = UUID.randomUUID();
        Instant acknowledgedAt = clock.instant();
        FindingAcknowledgement proposed = new FindingAcknowledgement(
                acknowledgementId,
                result.id(),
                currentUser.userId(),
                acknowledgedAt,
                command.requestId(),
                command.traceId(),
                result.ruleVersion());
        String fingerprint = deterministicFingerprint(
                currentUser.userId(), result.id(), task.id(), result.ruleVersion());
        flowDebugLogger.log(
                "REVIEW_ACK_START", "REVIEW", "APPLICATION", "acknowledgeFinding",
                safeFields(result, task, null));

        try {
            FindingAcknowledgement persisted = transactionCoordinator.create(
                    proposed, result, task, command, fingerprint);
            flowDebugLogger.log(
                    "REVIEW_ACK_TX_COMMIT", "REVIEW", "APPLICATION", "acknowledgeFinding",
                    safeFields(result, task, persisted.id().equals(proposed.id()) ? "CREATED" : "REPLAY"));
            return FindingAcknowledgementView.from(persisted, userIdentityQueryPort);
        } catch (FindingAcknowledgementTransactionCoordinator.AcknowledgementInsertFailure insertFailure) {
            return classifyInsertFailure(result, task, insertFailure);
        } catch (BusinessException businessException) {
            if (businessException.errorCode() == BusinessErrorCode.CONFLICT) {
                logConflict(result, task);
            }
            throw businessException;
        }
    }

    /** Resolves mutable display names only while projecting durable task acknowledgements. */
    public List<FindingAcknowledgementView> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return acknowledgementReadRepository.findByTaskId(taskId).stream()
                .map(acknowledgement -> FindingAcknowledgementView.from(
                        acknowledgement, userIdentityQueryPort))
                .toList();
    }

    private FindingAcknowledgementView classifyInsertFailure(
            AuditCheckResult result,
            AuditTask task,
            FindingAcknowledgementTransactionCoordinator.AcknowledgementInsertFailure insertFailure) {
        Optional<FindingAcknowledgement> winner;
        try {
            winner = acknowledgementRepository.findByCheckResultId(result.id());
        } catch (RuntimeException readFailure) {
            insertFailure.addSuppressed(readFailure);
            throw insertFailure;
        }
        if (winner.isEmpty()) {
            throw insertFailure;
        }
        if (!winner.orElseThrow().checkResultId().equals(result.id())) {
            throw new IllegalStateException("acknowledgement conflict winner has mismatched result identity");
        }
        logConflict(result, task);
        throw new BusinessException(BusinessErrorCode.CONFLICT, "review finding is already acknowledged");
    }

    private void validateEligible(AuditCheckResult result) {
        boolean eligible = result.checkStatus() == CheckStatus.NOT_EVALUATED
                        && result.riskLevel() == RiskLevel.YELLOW
                || result.checkStatus() == CheckStatus.FAIL
                        && result.riskLevel() == RiskLevel.RED;
        if (!eligible) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "review finding status and risk are not acknowledgement-eligible");
        }
    }

    private String deterministicFingerprint(
            String actor,
            UUID checkResultId,
            UUID taskId,
            long ruleVersion) {
        String canonical = String.join(
                "\n",
                IDEMPOTENCY_SCOPE,
                actor,
                checkResultId.toString(),
                taskId.toString(),
                Long.toString(ruleVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java 17", exception);
        }
    }

    private void logConflict(AuditCheckResult result, AuditTask task) {
        flowDebugLogger.log(
                "REVIEW_ACK_CONFLICT", "REVIEW", "APPLICATION", "acknowledgeFinding",
                safeFields(result, task, "CONFLICT"));
    }

    private Map<String, Object> safeFields(
            AuditCheckResult result,
            AuditTask task,
            String outcome) {
        if (outcome == null) {
            return Map.of(
                    "checkResultId", result.id(),
                    "taskId", task.id(),
                    "status", result.checkStatus(),
                    "risk", result.riskLevel(),
                    "ruleVersion", result.ruleVersion());
        }
        return Map.of(
                "checkResultId", result.id(),
                "taskId", task.id(),
                "status", result.checkStatus(),
                "risk", result.riskLevel(),
                "ruleVersion", result.ruleVersion(),
                "outcome", outcome);
    }
}
