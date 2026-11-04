package com.fuquan.audit.review.application;

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
import com.fuquan.audit.review.application.port.FindingAcknowledgementRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Owns the single short transaction for an acknowledgement's durable facts. */
@Component
public class FindingAcknowledgementTransactionCoordinator {

    public static final String RESULT_RESOURCE_TYPE = "REVIEW_FINDING_ACKNOWLEDGEMENT";

    private final FindingAcknowledgementRepository acknowledgementRepository;
    private final IdempotencyPort idempotencyPort;
    private final OperationAuditPort operationAuditPort;
    private final FlowDebugLogger flowDebugLogger;

    public FindingAcknowledgementTransactionCoordinator(
            FindingAcknowledgementRepository acknowledgementRepository,
            IdempotencyPort idempotencyPort,
            OperationAuditPort operationAuditPort,
            FlowDebugLogger flowDebugLogger) {
        this.acknowledgementRepository = Objects.requireNonNull(
                acknowledgementRepository, "acknowledgementRepository must not be null");
        this.idempotencyPort = Objects.requireNonNull(idempotencyPort, "idempotencyPort must not be null");
        this.operationAuditPort = Objects.requireNonNull(
                operationAuditPort, "operationAuditPort must not be null");
        this.flowDebugLogger = Objects.requireNonNull(flowDebugLogger, "flowDebugLogger must not be null");
    }

    /** Claims and commits the fact, operation audit, and idempotency success in one transaction. */
    @Transactional
    public FindingAcknowledgement create(
            FindingAcknowledgement acknowledgement,
            AuditCheckResult result,
            AuditTask task,
            AcknowledgeFindingCommand command,
            String fingerprint) {
        Objects.requireNonNull(acknowledgement, "acknowledgement must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        flowDebugLogger.log(
                "REVIEW_ACK_TX_ENTER", "REVIEW", "APPLICATION", "acknowledgeFinding",
                safeFields(result, task));

        IdempotencyClaim claim = idempotencyPort.claim(new IdempotencyRequest(
                FindingAcknowledgementService.IDEMPOTENCY_SCOPE,
                command.idempotencyKey(),
                acknowledgement.acknowledgedBy(),
                "AUDIT_CHECK_RESULT",
                result.id().toString(),
                fingerprint,
                RESULT_RESOURCE_TYPE,
                acknowledgement.id().toString(),
                command.requestId(),
                command.traceId(),
                acknowledgement.acknowledgedAt()));
        if (!claim.acquired()) {
            return replayOrConflict(claim.record(), acknowledgement, result, task, fingerprint);
        }

        try {
            acknowledgementRepository.create(acknowledgement);
        } catch (RuntimeException insertFailure) {
            rollbackLocalClaim(claim.record(), insertFailure);
            throw new AcknowledgementInsertFailure(insertFailure);
        }

        try {
            operationAuditPort.append(auditEvent(acknowledgement, result, task));
            idempotencyPort.markSucceeded(
                    claim.record(), RESULT_RESOURCE_TYPE, acknowledgement.id().toString(),
                    acknowledgement.acknowledgedAt());
            return acknowledgement;
        } catch (RuntimeException writeFailure) {
            rollbackLocalClaim(claim.record(), writeFailure);
            throw writeFailure;
        }
    }

    private FindingAcknowledgement replayOrConflict(
            IdempotencyRecord record,
            FindingAcknowledgement proposed,
            AuditCheckResult result,
            AuditTask task,
            String fingerprint) {
        if (!Objects.equals(record.requestFingerprint(), fingerprint)) {
            throw conflict("idempotency key fingerprint conflict");
        }
        if (record.status() != IdempotencyStatus.SUCCEEDED) {
            throw conflict(record.status() == IdempotencyStatus.IN_PROGRESS
                    ? "idempotent acknowledgement is in progress"
                    : "idempotent acknowledgement previously failed");
        }
        validateSucceededRecord(record, proposed, result, task);
        UUID durableAcknowledgementId;
        try {
            durableAcknowledgementId = UUID.fromString(record.resultResourceId());
        } catch (RuntimeException invalidIdentity) {
            throw new IllegalStateException("succeeded acknowledgement record has invalid result identity", invalidIdentity);
        }
        FindingAcknowledgement durable = acknowledgementRepository.findByCheckResultId(result.id())
                .orElseThrow(() -> new IllegalStateException(
                        "succeeded acknowledgement record has no durable acknowledgement"));
        if (!durable.id().equals(durableAcknowledgementId)
                || !durable.checkResultId().equals(result.id())
                || !durable.acknowledgedBy().equals(proposed.acknowledgedBy())
                || durable.ruleVersion() != result.ruleVersion()) {
            throw new IllegalStateException("succeeded acknowledgement record does not match durable fact");
        }
        return durable;
    }

    private void validateSucceededRecord(
            IdempotencyRecord record,
            FindingAcknowledgement proposed,
            AuditCheckResult result,
            AuditTask task) {
        if (!FindingAcknowledgementService.IDEMPOTENCY_SCOPE.equals(record.scope())
                || !proposed.acknowledgedBy().equals(record.actorId())
                || !"AUDIT_CHECK_RESULT".equals(record.resourceType())
                || !result.id().toString().equals(record.resourceId())
                || !RESULT_RESOURCE_TYPE.equals(record.resultResourceType())
                || record.resultResourceId() == null
                || record.resultResourceId().isBlank()
                || !result.taskId().equals(task.id())) {
            throw new IllegalStateException("succeeded acknowledgement record has mismatched semantic identity");
        }
    }

    private OperationAuditEvent auditEvent(
            FindingAcknowledgement acknowledgement,
            AuditCheckResult result,
            AuditTask task) {
        String safeDetail = "status=" + result.checkStatus()
                + ";risk=" + result.riskLevel()
                + ";ruleVersion=" + result.ruleVersion();
        return new OperationAuditEvent(
                "REVIEW_FINDING_ACKNOWLEDGED",
                "REVIEW",
                "ACKNOWLEDGE_FINDING",
                acknowledgement.acknowledgedBy(),
                null,
                "AUDIT_CHECK_RESULT",
                result.id().toString(),
                task.id().toString(),
                OperationAuditResultStatus.SUCCESS,
                "ACKNOWLEDGED",
                "Review finding acknowledged",
                safeDetail,
                acknowledgement.requestId(),
                acknowledgement.traceId(),
                null,
                acknowledgement.acknowledgedAt());
    }

    private Map<String, Object> safeFields(AuditCheckResult result, AuditTask task) {
        return Map.of(
                "checkResultId", result.id(),
                "taskId", task.id(),
                "status", result.checkStatus(),
                "risk", result.riskLevel(),
                "ruleVersion", result.ruleVersion());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    private void rollbackLocalClaim(IdempotencyRecord record, RuntimeException originalFailure) {
        try {
            idempotencyPort.rollbackUncommittedClaim(record);
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    /** Signals that only an outside-transaction durable reread may classify an insert race. */
    static final class AcknowledgementInsertFailure extends RuntimeException {
        private AcknowledgementInsertFailure(RuntimeException cause) {
            super("finding acknowledgement insert failed", cause);
        }
    }
}
