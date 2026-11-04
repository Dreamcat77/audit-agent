package com.fuquan.audit.contract.application;

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
import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.contract.application.port.MaterialBindingRepository;
import com.fuquan.audit.contract.application.port.ReviewDocumentRepository;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import com.fuquan.audit.contract.domain.ReviewDocumentStatus;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ReviewSession 写模型的独立短事务协调器。 */
@Component
public class ContractReviewSessionTransactionCoordinator {
    static final String SESSION_RESOURCE = "CONTRACT_REVIEW_SESSION";
    static final String DOCUMENT_RESOURCE = "CONTRACT_REVIEW_DOCUMENT";

    private final ContractReviewSessionRepository sessionRepository;
    private final ReviewDocumentRepository documentRepository;
    private final MaterialBindingRepository bindingRepository;
    private final IdempotencyPort idempotencyPort;
    private final OperationAuditPort operationAuditPort;

    public ContractReviewSessionTransactionCoordinator(
            ContractReviewSessionRepository sessionRepository,
            ReviewDocumentRepository documentRepository,
            MaterialBindingRepository bindingRepository,
            IdempotencyPort idempotencyPort,
            OperationAuditPort operationAuditPort) {
        this.sessionRepository = sessionRepository;
        this.documentRepository = documentRepository;
        this.bindingRepository = bindingRepository;
        this.idempotencyPort = idempotencyPort;
        this.operationAuditPort = operationAuditPort;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewSessionOperationResult createSession(
            Contract contract,
            ContractReviewSession candidate,
            CurrentUser user,
            String idempotencyKey,
            String fingerprint,
            ReviewRequestMetadata metadata,
            Instant now) {
        IdempotencyClaim claim = claim(
                ContractReviewSessionService.CREATE_SCOPE, idempotencyKey, user,
                "CONTRACT", contract.id().toString(), fingerprint,
                SESSION_RESOURCE, candidate.id().toString(), metadata, now);
        if (!claim.acquired()) {
            IdempotencyRecord existing = requireSameRequest(
                    claim.record(), user, "CONTRACT", contract.id().toString(), fingerprint);
            if (existing.status() == IdempotencyStatus.SUCCEEDED) {
                return new ReviewSessionOperationResult(requireSession(resultId(existing)), true);
            }
            throw terminalConflict(existing, "review session creation");
        }
        try {
            sessionRepository.create(candidate);
            appendAudit(
                    "REVIEW_SESSION_CREATED", "REVIEW_SESSION_CREATE", OperationAuditResultStatus.SUCCESS,
                    user, candidate.id(), null, "Review session created", null, metadata, now);
            idempotencyPort.markSucceeded(claim.record(), SESSION_RESOURCE, candidate.id().toString(), now);
            return new ReviewSessionOperationResult(candidate, false);
        } catch (RuntimeException exception) {
            idempotencyPort.rollbackUncommittedClaim(claim.record());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewDocumentUploadStart startUpload(
            UUID candidateDocumentId,
            UUID sessionId,
            ReviewDocumentKind kind,
            long expectedVersion,
            ValidatedReviewFile file,
            StorageKey storageKey,
            CurrentUser user,
            String idempotencyKey,
            String fingerprint,
            ReviewRequestMetadata metadata,
            Instant now) {
        IdempotencyClaim claim = claim(
                ContractReviewSessionService.UPLOAD_SCOPE, idempotencyKey, user,
                SESSION_RESOURCE, sessionId.toString(), fingerprint,
                DOCUMENT_RESOURCE, candidateDocumentId.toString(), metadata, now);
        if (!claim.acquired()) {
            IdempotencyRecord existing = requireSameRequest(
                    claim.record(), user, SESSION_RESOURCE, sessionId.toString(), fingerprint);
            if (existing.status() == IdempotencyStatus.SUCCEEDED) {
                return ReviewDocumentUploadStart.replay(replayUpload(existing));
            }
            if (existing.status() == IdempotencyStatus.FAILED) {
                throw terminalConflict(existing, "document upload");
            }
            ReviewDocument pending = documentRepository.findById(resultId(existing))
                    .orElseThrow(() -> new BusinessException(
                            BusinessErrorCode.CONFLICT, "document upload is in progress"));
            if (!pending.sessionId().equals(sessionId)
                    || pending.status() != ReviewDocumentStatus.UPLOAD_PENDING) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotent upload state is inconsistent");
            }
            MaterialBinding binding = bindingRepository
                    .findBySessionAndSlot(sessionId, pending.slotKey()).orElse(null);
            return ReviewDocumentUploadStart.executing(existing, pending, binding);
        }

        try {
            ContractReviewSession session = requireSession(sessionId);
            requirePreparingVersion(session, expectedVersion);
            if (!documentRepository.findPendingBySessionId(sessionId).isEmpty()) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "another upload is pending for this session");
            }
            sessionRepository.advancePreparingVersion(sessionId, expectedVersion, now);

            String slotKey = kind.newSlotKey(candidateDocumentId);
            MaterialBinding binding = bindingRepository.findBySessionAndSlot(sessionId, slotKey).orElse(null);
            UUID replaces = binding == null ? null : binding.boundDocumentId();
            ReviewDocument document = ReviewDocument.pending(
                    candidateDocumentId, sessionId, kind, slotKey, file.safeFileName(), file.extension(),
                    file.canonicalContentType(), file.stagedFile().size(), file.stagedFile().sha256(),
                    storageKey.value(), replaces, expectedVersion, user.userId(), now);
            documentRepository.create(document);
            appendAudit(
                    "REVIEW_DOCUMENT_UPLOAD_STARTED", "REVIEW_DOCUMENT_UPLOAD_START",
                    OperationAuditResultStatus.SUCCESS, user, sessionId, document.id(),
                    "Review document upload started", kind.name(), metadata, now);
            return ReviewDocumentUploadStart.executing(claim.record(), document, binding);
        } catch (RuntimeException exception) {
            idempotencyPort.rollbackUncommittedClaim(claim.record());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewDocumentOperationResult completeUpload(
            ReviewDocumentUploadStart start,
            CurrentUser user,
            ReviewRequestMetadata metadata,
            Instant now) {
        IdempotencyRecord idempotency = currentIdempotency(
                start.idempotency().scope(), start.idempotency().idempotencyKey());
        if (idempotency.status() == IdempotencyStatus.SUCCEEDED) {
            return replayUpload(idempotency);
        }
        if (idempotency.status() != IdempotencyStatus.IN_PROGRESS) {
            throw terminalConflict(idempotency, "document upload");
        }
        ReviewDocument pending = documentRepository.findById(start.document().id())
                .orElseThrow(() -> new IllegalStateException("pending upload document is missing"));
        if (pending.status() != ReviewDocumentStatus.UPLOAD_PENDING
                || pending.fileSize() != start.document().fileSize()
                || !pending.sha256().equals(start.document().sha256())
                || !pending.storageKey().equals(start.document().storageKey())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "pending upload metadata conflict");
        }
        ContractReviewSession session = sessionRepository.advancePreparingVersion(
                pending.sessionId(), pending.reservedSessionVersion(), now);
        ReviewDocument available = documentRepository.markAvailable(pending.id(), pending.versionNo(), now);

        MaterialBinding current = bindingRepository
                .findBySessionAndSlot(available.sessionId(), available.slotKey()).orElse(null);
        MaterialBinding bound;
        if (current == null) {
            bound = MaterialBinding.activeFor(available, now);
            bindingRepository.create(bound);
        } else {
            bound = bindingRepository.bind(
                    current.id(), current.boundDocumentId(), current.active(), current.versionNo(),
                    available.id(), now);
        }
        String event = available.replacesDocumentId() == null
                ? "REVIEW_DOCUMENT_ATTACHED" : "REVIEW_DOCUMENT_REPLACED";
        appendAudit(
                event, event, OperationAuditResultStatus.SUCCESS, user, available.sessionId(),
                available.id(), "Review document attached", available.documentKind().name(), metadata, now);
        idempotencyPort.markSucceeded(idempotency, DOCUMENT_RESOURCE, available.id().toString(), now);
        return new ReviewDocumentOperationResult(available, bound, session, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failUpload(
            ReviewDocumentUploadStart start,
            CurrentUser user,
            ReviewRequestMetadata metadata,
            String safeFailureType,
            Instant now) {
        IdempotencyRecord idempotency = currentIdempotency(
                start.idempotency().scope(), start.idempotency().idempotencyKey());
        if (idempotency.status() != IdempotencyStatus.IN_PROGRESS) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "upload is already terminal");
        }
        ReviewDocument pending = documentRepository.findById(start.document().id())
                .orElseThrow(() -> new IllegalStateException("pending upload document is missing"));
        if (pending.status() != ReviewDocumentStatus.UPLOAD_PENDING) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "upload document is already terminal");
        }
        sessionRepository.advancePreparingVersion(pending.sessionId(), pending.reservedSessionVersion(), now);
        documentRepository.markFailed(pending.id(), pending.versionNo(), now);
        appendAudit(
                "REVIEW_DOCUMENT_UPLOAD_FAILED", "REVIEW_DOCUMENT_UPLOAD_FAILURE",
                OperationAuditResultStatus.FAILURE, user, pending.sessionId(), pending.id(),
                "Review document upload failed", safeFailureType, metadata, now);
        idempotencyPort.markFailed(idempotency, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewSessionOperationResult remove(
            RemoveReviewDocumentCommand command,
            CurrentUser user,
            String fingerprint,
            Instant now) {
        IdempotencyClaim claim = claim(
                ContractReviewSessionService.REMOVE_SCOPE, command.idempotencyKey(), user,
                SESSION_RESOURCE, command.sessionId().toString(), fingerprint,
                SESSION_RESOURCE, command.sessionId().toString(), command.metadata(), now);
        if (!claim.acquired()) {
            IdempotencyRecord existing = requireSameRequest(
                    claim.record(), user, SESSION_RESOURCE, command.sessionId().toString(), fingerprint);
            if (existing.status() == IdempotencyStatus.SUCCEEDED) {
                return new ReviewSessionOperationResult(requireSession(resultId(existing)), true);
            }
            throw terminalConflict(existing, "material removal");
        }
        try {
            ContractReviewSession session = requireSession(command.sessionId());
            requirePreparingVersion(session, command.expectedVersion());
            requireNoPending(command.sessionId());
            ReviewDocument document = documentRepository
                    .findBySessionAndId(command.sessionId(), command.documentId())
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
            MaterialBinding binding = bindingRepository
                    .findBySessionAndSlot(command.sessionId(), document.slotKey())
                    .filter(MaterialBinding::active)
                    .filter(value -> value.boundDocumentId().equals(document.id()))
                    .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "document is not current"));
            ContractReviewSession updated = sessionRepository.advancePreparingVersion(
                    session.id(), session.versionNo(), now);
            bindingRepository.deactivate(binding.id(), document.id(), binding.versionNo(), now);
            appendAudit(
                    "REVIEW_DOCUMENT_REMOVED", "REVIEW_DOCUMENT_REMOVE", OperationAuditResultStatus.SUCCESS,
                    user, session.id(), document.id(), "Review document removed from current materials",
                    document.documentKind().name(), command.metadata(), now);
            idempotencyPort.markSucceeded(claim.record(), SESSION_RESOURCE, updated.id().toString(), now);
            return new ReviewSessionOperationResult(updated, false);
        } catch (RuntimeException exception) {
            idempotencyPort.rollbackUncommittedClaim(claim.record());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewSessionOperationResult confirm(
            ConfirmReviewMaterialsCommand command,
            CurrentUser user,
            String fingerprint,
            Instant now) {
        IdempotencyClaim claim = claim(
                ContractReviewSessionService.CONFIRM_SCOPE, command.idempotencyKey(), user,
                SESSION_RESOURCE, command.sessionId().toString(), fingerprint,
                SESSION_RESOURCE, command.sessionId().toString(), command.metadata(), now);
        if (!claim.acquired()) {
            IdempotencyRecord existing = requireSameRequest(
                    claim.record(), user, SESSION_RESOURCE, command.sessionId().toString(), fingerprint);
            if (existing.status() == IdempotencyStatus.SUCCEEDED) {
                return new ReviewSessionOperationResult(requireSession(resultId(existing)), true);
            }
            throw terminalConflict(existing, "materials confirmation");
        }
        try {
            ContractReviewSession session = requireSession(command.sessionId());
            requirePreparingVersion(session, command.expectedVersion());
            requireNoPending(command.sessionId());
            requireCoreMaterials(command.sessionId());
            ContractReviewSession ready = sessionRepository.confirmMaterials(
                    session.id(), session.versionNo(), now);
            appendAudit(
                    "REVIEW_MATERIALS_CONFIRMED", "REVIEW_MATERIALS_CONFIRM",
                    OperationAuditResultStatus.SUCCESS, user, session.id(), null,
                    "Review materials confirmed", "READY", command.metadata(), now);
            idempotencyPort.markSucceeded(claim.record(), SESSION_RESOURCE, ready.id().toString(), now);
            return new ReviewSessionOperationResult(ready, false);
        } catch (RuntimeException exception) {
            idempotencyPort.rollbackUncommittedClaim(claim.record());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewSessionOperationResult deleteSession(
            DeleteReviewSessionCommand command,
            CurrentUser user,
            String fingerprint,
            Instant now) {
        IdempotencyClaim claim = claim(
                ContractReviewSessionService.DELETE_SCOPE, command.idempotencyKey(), user,
                SESSION_RESOURCE, command.sessionId().toString(), fingerprint,
                SESSION_RESOURCE, command.sessionId().toString(), command.metadata(), now);
        if (!claim.acquired()) {
            IdempotencyRecord existing = requireSameRequest(
                    claim.record(), user, SESSION_RESOURCE, command.sessionId().toString(), fingerprint);
            if (existing.status() == IdempotencyStatus.SUCCEEDED) {
                return new ReviewSessionOperationResult(requireSession(resultId(existing)), true);
            }
            throw terminalConflict(existing, "review session deletion");
        }
        try {
            ContractReviewSession session = requireSession(command.sessionId());
            if (session.deleted() || session.versionNo() != command.expectedVersion()) {
                throw new BusinessException(
                        BusinessErrorCode.CONFLICT, "review session status or version changed");
            }
            requireNoPending(command.sessionId());
            ContractReviewSession deleted = sessionRepository.softDelete(
                    session.id(), session.versionNo(), user.userId(), now);
            appendAudit(
                    "REVIEW_SESSION_DELETED", "REVIEW_SESSION_DELETE",
                    OperationAuditResultStatus.SUCCESS, user, session.id(), null,
                    "Review session deleted", "status=" + session.status()
                            + ";expectedVersion=" + command.expectedVersion(),
                    command.metadata(), now);
            idempotencyPort.markSucceeded(
                    claim.record(), SESSION_RESOURCE, deleted.id().toString(), now);
            return new ReviewSessionOperationResult(deleted, false);
        } catch (RuntimeException exception) {
            idempotencyPort.rollbackUncommittedClaim(claim.record());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> readIdempotency(String scope, String key) {
        return idempotencyPort.findByScopeAndKey(scope, key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> readUploadIdempotency(UUID documentId) {
        return idempotencyPort.findByResultResource(DOCUMENT_RESOURCE, documentId.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ReviewDocumentOperationResult replayUploadResult(IdempotencyRecord record) {
        return replayUpload(record);
    }

    ReviewDocumentOperationResult replayUpload(IdempotencyRecord record) {
        ReviewDocument document = documentRepository.findById(resultId(record))
                .orElseThrow(() -> new IllegalStateException("idempotency result document is missing"));
        ContractReviewSession session = requireSession(document.sessionId());
        MaterialBinding binding = bindingRepository
                .findBySessionAndSlot(document.sessionId(), document.slotKey()).orElse(null);
        return new ReviewDocumentOperationResult(document, binding, session, true);
    }

    void requireCoreMaterials(UUID sessionId) {
        Set<ReviewDocumentKind> available = EnumSet.noneOf(ReviewDocumentKind.class);
        List<MaterialBinding> active = bindingRepository.findBySessionId(sessionId).stream()
                .filter(MaterialBinding::active).toList();
        for (MaterialBinding binding : active) {
            ReviewDocument document = documentRepository.findById(binding.boundDocumentId()).orElse(null);
            if (document != null
                    && document.sessionId().equals(sessionId)
                    && document.status() == ReviewDocumentStatus.AVAILABLE) {
                available.add(document.documentKind());
            }
        }
        if (!available.contains(ReviewDocumentKind.CONTRACT_BODY)
                || !available.contains(ReviewDocumentKind.AWARD_NOTICE)) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "CONTRACT_BODY and AWARD_NOTICE are required before confirmation");
        }
    }

    private IdempotencyClaim claim(
            String scope, String key, CurrentUser user,
            String resourceType, String resourceId, String fingerprint,
            String resultType, String resultId, ReviewRequestMetadata metadata, Instant now) {
        return idempotencyPort.claim(new IdempotencyRequest(
                scope, key, user.userId(), resourceType, resourceId, fingerprint,
                resultType, resultId, metadata.requestId(), metadata.traceId(), now));
    }

    private IdempotencyRecord requireSameRequest(
            IdempotencyRecord record,
            CurrentUser user,
            String resourceType,
            String resourceId,
            String fingerprint) {
        if (!record.actorId().equals(user.userId())
                || !record.resourceType().equals(resourceType)
                || !record.resourceId().equals(resourceId)
                || !record.requestFingerprint().equals(fingerprint)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        return record;
    }

    private ContractReviewSession requireSession(UUID id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
    }

    private void requirePreparingVersion(ContractReviewSession session, long expectedVersion) {
        if (session.sourceKind() != com.fuquan.audit.contract.domain.ReviewSessionSourceKind.LEGACY_REVIEW_DOCUMENT
                || session.deleted()
                || session.status() != ReviewSessionStatus.PREPARING_MATERIALS
                || session.versionNo() != expectedVersion) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "review session status or version changed");
        }
    }

    private void requireNoPending(UUID sessionId) {
        if (!documentRepository.findPendingBySessionId(sessionId).isEmpty()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "review session has a pending upload");
        }
    }

    private IdempotencyRecord currentIdempotency(String scope, String key) {
        return idempotencyPort.findByScopeAndKey(scope, key)
                .orElseThrow(() -> new IllegalStateException("durable idempotency record is missing"));
    }

    private UUID resultId(IdempotencyRecord record) {
        if (record.resultResourceId() == null) {
            throw new IllegalStateException("idempotency result resource is missing");
        }
        return UUID.fromString(record.resultResourceId());
    }

    private BusinessException terminalConflict(IdempotencyRecord record, String operation) {
        String message = record.status() == IdempotencyStatus.IN_PROGRESS
                ? operation + " is in progress"
                : operation + " previously failed; use a new Idempotency-Key";
        return new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    private void appendAudit(
            String eventType,
            String action,
            OperationAuditResultStatus result,
            CurrentUser user,
            UUID sessionId,
            UUID documentId,
            String description,
            String detail,
            ReviewRequestMetadata metadata,
            Instant now) {
        boolean documentEvent = documentId != null;
        String resourceType = documentEvent ? DOCUMENT_RESOURCE : SESSION_RESOURCE;
        String resourceId = documentEvent ? documentId.toString() : sessionId.toString();
        String safeDetail = documentEvent
                ? "sessionId=" + sessionId + (detail == null ? "" : ";context=" + detail)
                : detail;
        operationAuditPort.append(new OperationAuditEvent(
                eventType, "CONTRACT", action, user.userId(), user.username(),
                resourceType, resourceId, null,
                result, result == OperationAuditResultStatus.SUCCESS ? "SUCCESS" : "FAILED",
                description, safeDetail, metadata.requestId(), metadata.traceId(), metadata.sourceIp(), now));
    }
}
