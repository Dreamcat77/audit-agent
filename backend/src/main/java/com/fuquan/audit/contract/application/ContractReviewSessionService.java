package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.application.port.IdempotencyConcurrencyException;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.SafeExceptionDiagnostics;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.contract.application.port.FileStorageContentConflictException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.FileStorageUserRejectionException;
import com.fuquan.audit.contract.application.port.MaterialBindingRepository;
import com.fuquan.audit.contract.application.port.ReviewDocumentRepository;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import com.fuquan.audit.contract.domain.ReviewDocumentStatus;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ReviewSession、真实材料接入和 READY 转换的应用编排。 */
@Service
public class ContractReviewSessionService {
    public static final String REVIEW_TYPE = "F02_DETERMINISTIC";
    public static final String CREATE_SCOPE = "CONTRACT_REVIEW_SESSION_CREATE";
    public static final String UPLOAD_SCOPE = "CONTRACT_REVIEW_DOCUMENT_UPLOAD";
    public static final String REMOVE_SCOPE = "CONTRACT_REVIEW_MATERIAL_REMOVE";
    public static final String CONFIRM_SCOPE = "CONTRACT_REVIEW_MATERIALS_CONFIRM";
    public static final String DELETE_SCOPE = "CONTRACT_REVIEW_SESSION_DELETE";

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Logger log = LoggerFactory.getLogger(ContractReviewSessionService.class);

    private final ContractRepository contractRepository;
    private final ContractReviewSessionRepository sessionRepository;
    private final ReviewDocumentRepository documentRepository;
    private final MaterialBindingRepository bindingRepository;
    private final FileStoragePort storage;
    private final ReviewFileValidator fileValidator;
    private final ContractReviewSessionTransactionCoordinator coordinator;
    private final AuthContext authContext;
    private final Clock clock;
    private final FlowDebugLogger flowDebugLogger;
    private final long sizeLimit;
    private final Duration recoveryAge;
    private com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort projectContracts;

    @org.springframework.beans.factory.annotation.Autowired
    public void setProjectContracts(com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort projectContracts) {
        this.projectContracts = projectContracts;
    }

    public ContractReviewSessionService(
            ContractRepository contractRepository,
            ContractReviewSessionRepository sessionRepository,
            ReviewDocumentRepository documentRepository,
            MaterialBindingRepository bindingRepository,
            FileStoragePort storage,
            ReviewFileValidator fileValidator,
            ContractReviewSessionTransactionCoordinator coordinator,
            AuthContext authContext,
            Clock clock,
            ReviewFilePolicy filePolicy,
            FlowDebugLogger flowDebugLogger) {
        this.contractRepository = contractRepository;
        this.sessionRepository = sessionRepository;
        this.documentRepository = documentRepository;
        this.bindingRepository = bindingRepository;
        this.storage = storage;
        this.fileValidator = fileValidator;
        this.coordinator = coordinator;
        this.authContext = authContext;
        this.clock = clock;
        this.flowDebugLogger = flowDebugLogger;
        this.sizeLimit = filePolicy.sizeLimit();
        this.recoveryAge = filePolicy.pendingRecoveryAge();
    }

    public ReviewSessionOperationResult create(CreateReviewSessionCommand command) {
        flow("REVIEW_SESSION_CREATE_START", "APPLICATION", "createSession", Map.of());
        validateKey(command.idempotencyKey());
        CurrentUser user = currentUser();
        if (projectContracts != null && projectContracts.findByContractId(command.contractId()).isPresent()
                && coordinator.readIdempotency(CREATE_SCOPE, command.idempotencyKey()).isEmpty()) {
            throw conflict("Create a Project material reference session through the Project workflow");
        }
        Contract contract = contractRepository.findById(command.contractId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        String name;
        String remark;
        try {
            name = ContractReviewSession.normalizeName(command.name());
            remark = ContractReviewSession.normalizeRemark(command.remark());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, exception.getMessage());
        }
        Instant now = clock.instant();
        ContractReviewSession candidate = ContractReviewSession.create(
                UUID.randomUUID(), contract.id(), contract.versionNo(), name, REVIEW_TYPE,
                remark, user.userId(), now);
        String fingerprint = ReviewSessionFingerprint.create()
                .field("scope", CREATE_SCOPE)
                .field("actor", user.userId())
                .field("contractId", contract.id())
                .field("contractVersion", contract.versionNo())
                .field("name", name)
                .field("remark", remark)
                .hex();
        try {
            ReviewSessionOperationResult result = coordinator.createSession(
                    contract, candidate, user, command.idempotencyKey(), fingerprint,
                    requireMetadata(command.metadata()), now);
            flow("REVIEW_SESSION_CREATE_COMPLETE", "APPLICATION", "createSession", Map.of(
                    "sessionId", result.session().id(),
                    "sessionStatus", result.session().status(),
                    "currentVersion", result.session().versionNo(),
                    "replayed", result.replayed()));
            return result;
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            throw conflict("review session creation conflicted");
        }
    }

    @Transactional(readOnly = true)
    public List<ContractReviewSession> list(UUID contractId) {
        if (contractRepository.findById(contractId).isEmpty()) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        }
        return sessionRepository.findByContractId(contractId);
    }

    @Transactional(readOnly = true)
    public ContractReviewSession get(UUID sessionId) {
        return requireSession(sessionId);
    }

    public ReviewDocumentOperationResult upload(UploadReviewDocumentCommand command) {
        validateKey(command.idempotencyKey());
        CurrentUser user = currentUser();
        requireLegacySource(requireSession(command.sessionId()));
        ReviewRequestMetadata metadata = requireMetadata(command.metadata());
        if (coordinator.readIdempotency(UPLOAD_SCOPE, command.idempotencyKey()).isEmpty()) {
            requireUploadCandidate(command.sessionId(), command.expectedVersion());
        }

        StagedFile staged;
        flow("REVIEW_UPLOAD_STAGE_START", "STORAGE", "uploadDocument", Map.of(
                "sessionId", command.sessionId(),
                "documentKind", command.documentKind(),
                "expectedVersion", command.expectedVersion()));
        try (InputStream input = command.inputStream()) {
            staged = storage.stage(input, sizeLimit);
            flow("REVIEW_UPLOAD_STAGE_COMPLETE", "STORAGE", "uploadDocument", Map.of(
                    "sessionId", command.sessionId(),
                    "documentKind", command.documentKind(),
                    "fileSize", staged.size()));
        } catch (FileStorageUserRejectionException exception) {
            flow("REVIEW_UPLOAD_STAGE_REJECTED", "STORAGE", "uploadDocument", Map.of(
                    "sessionId", command.sessionId(),
                    "documentKind", command.documentKind(),
                    "recoveryDecision", exception.reason()));
            BusinessErrorCode code = exception.reason() == FileStorageUserRejectionException.Reason.SIZE_LIMIT
                    ? BusinessErrorCode.PAYLOAD_TOO_LARGE
                    : BusinessErrorCode.BUSINESS_VALIDATION_FAILED;
            throw new BusinessException(code, exception.getMessage());
        } catch (FileStorageException exception) {
            flow("REVIEW_UPLOAD_STAGE_FAILED", "STORAGE", "uploadDocument", Map.of(
                    "sessionId", command.sessionId(),
                    "documentKind", command.documentKind(),
                    "storageReason", exception.reason()));
            logStorageFailure(
                    "STAGE", metadata, command.sessionId(), null, exception);
            throw new BusinessException(
                    BusinessErrorCode.INTERNAL_ERROR, "file storage is temporarily unavailable");
        } catch (IOException exception) {
            flow("REVIEW_UPLOAD_STAGE_FAILED", "STORAGE", "uploadDocument", Map.of(
                    "sessionId", command.sessionId(),
                    "documentKind", command.documentKind(),
                    "storageReason", storageReason(exception)));
            logStorageFailure(
                    "STAGE_STREAM", metadata, command.sessionId(), null, exception);
            throw new BusinessException(
                    BusinessErrorCode.INTERNAL_ERROR, "file storage is temporarily unavailable");
        }

        ValidatedReviewFile file;
        try {
            file = fileValidator.validate(staged, command.originalFileName(), command.contentType());
        } catch (RuntimeException exception) {
            storage.discard(staged);
            throw exception;
        }

        UUID candidateDocumentId = UUID.randomUUID();
        String fingerprint = uploadFingerprint(command, user, file);
        StorageKey candidateStorageKey = new StorageKey(ReviewSessionFingerprint.create()
                .field("scope", UPLOAD_SCOPE)
                .field("actor", user.userId())
                .field("sessionId", command.sessionId())
                .field("idempotencyKey", command.idempotencyKey())
                .hex());
        ReviewDocumentUploadStart start;
        try {
            flow("REVIEW_UPLOAD_START_TX_ENTER", "TRANSACTION", "startUpload", Map.of(
                    "sessionId", command.sessionId(),
                    "documentId", candidateDocumentId,
                    "documentKind", command.documentKind(),
                    "expectedVersion", command.expectedVersion()));
            start = coordinator.startUpload(
                    candidateDocumentId, command.sessionId(), command.documentKind(),
                    command.expectedVersion(), file, candidateStorageKey, user,
                    command.idempotencyKey(), fingerprint, metadata, clock.instant());
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            storage.discard(staged);
            throw conflict("document upload start conflicted");
        } catch (RuntimeException exception) {
            storage.discard(staged);
            throw exception;
        }
        if (start.isReplay()) {
            flow("REVIEW_UPLOAD_START_TX_COMMIT", "TRANSACTION", "startUpload", Map.of(
                    "sessionId", start.replay().session().id(),
                    "documentId", start.replay().document().id(),
                    "documentKind", start.replay().document().documentKind(),
                    "currentVersion", start.replay().session().versionNo(),
                    "replayed", true));
            storage.discard(staged);
            return start.replay();
        }
        flow("REVIEW_UPLOAD_START_TX_COMMIT", "TRANSACTION", "startUpload", Map.of(
                "sessionId", start.document().sessionId(),
                "documentId", start.document().id(),
                "documentKind", start.document().documentKind(),
                "reservedVersion", start.document().reservedSessionVersion(),
                "replayed", false));

        try {
            flow("REVIEW_UPLOAD_PUBLISH_START", "STORAGE", "publishDocument", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "fileSize", start.document().fileSize()));
            storage.promote(staged, new StorageKey(start.document().storageKey()));
            flow("REVIEW_UPLOAD_PUBLISH_COMPLETE", "STORAGE", "publishDocument", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "fileSize", start.document().fileSize()));
        } catch (FileStorageContentConflictException failure) {
            flow("REVIEW_UPLOAD_PUBLISH_FAILED", "STORAGE", "publishDocument", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "recoveryDecision", "CONTENT_CONFLICT",
                    "storageReason", failure.reason()));
            storage.discard(staged);
            closeUploadFailure(start, user, metadata, failure);
            throw new BusinessException(BusinessErrorCode.CONFLICT, "stored file content conflicts");
        } catch (RuntimeException failure) {
            StorageFailureReason reason = storageReason(failure);
            flow("REVIEW_UPLOAD_PUBLISH_FAILED", "STORAGE", "publishDocument", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "documentKind", start.document().documentKind(),
                    "recoveryDecision", "INFRASTRUCTURE_FAILURE",
                    "storageReason", reason));
            logStorageFailure(
                    "PUBLISH", metadata, start.document().sessionId(),
                    start.document().id(), failure);
            storage.discard(staged);
            closeUploadFailure(start, user, metadata, failure);
            throw new BusinessException(
                    BusinessErrorCode.INTERNAL_ERROR, "file storage is temporarily unavailable");
        }

        try {
            flow("REVIEW_UPLOAD_COMPLETE_TX_ENTER", "TRANSACTION", "completeUpload", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "reservedVersion", start.document().reservedSessionVersion()));
            ReviewDocumentOperationResult result = coordinator.completeUpload(
                    start, user, metadata, clock.instant());
            flow("REVIEW_UPLOAD_COMPLETE_TX_COMMIT", "TRANSACTION", "completeUpload", Map.of(
                    "sessionId", result.session().id(),
                    "documentId", result.document().id(),
                    "documentStatus", result.document().status(),
                    "currentVersion", result.session().versionNo(),
                    "replayed", result.replayed()));
            return result;
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            return resolveCompleteOutcome(start, command.idempotencyKey());
        } catch (BusinessException exception) {
            return resolveCompleteOutcome(start, command.idempotencyKey());
        } catch (RuntimeException exception) {
            return resolveCompleteOutcome(start, command.idempotencyKey());
        } finally {
            storage.discard(staged);
        }
    }

    @Transactional(readOnly = true)
    public ReviewDocumentListResult documents(UUID sessionId, boolean includeHistory) {
        requireSession(sessionId);
        List<ReviewDocument> documents = documentRepository.findBySessionId(sessionId);
        Map<UUID, ReviewDocument> byId = new HashMap<>();
        documents.forEach(document -> byId.put(document.id(), document));
        Set<UUID> currentIds = new HashSet<>();
        List<ReviewDocumentView> current = new ArrayList<>();
        for (MaterialBinding binding : bindingRepository.findBySessionId(sessionId)) {
            if (!binding.active()) {
                continue;
            }
            ReviewDocument document = byId.get(binding.boundDocumentId());
            if (document != null && document.status() == ReviewDocumentStatus.AVAILABLE) {
                currentIds.add(document.id());
                current.add(new ReviewDocumentView(document, true));
            }
        }
        current.sort(Comparator.comparing(view -> view.document().slotKey()));
        List<ReviewDocumentView> history = includeHistory
                ? documents.stream()
                        .filter(document -> !currentIds.contains(document.id()))
                        .sorted(Comparator.comparing(ReviewDocument::createdAt).reversed()
                                .thenComparing(ReviewDocument::id, Comparator.reverseOrder()))
                        .map(document -> new ReviewDocumentView(document, false))
                        .toList()
                : List.of();
        return new ReviewDocumentListResult(current, history);
    }

    @Transactional(readOnly = true)
    public ReviewDocumentContent content(UUID sessionId, UUID documentId, boolean history) {
        requireSession(sessionId);
        ReviewDocument document = documentRepository.findBySessionAndId(sessionId, documentId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        if (document.status() != ReviewDocumentStatus.AVAILABLE) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        }
        boolean current = bindingRepository.findBySessionAndSlot(sessionId, document.slotKey())
                .filter(MaterialBinding::active)
                .map(binding -> binding.boundDocumentId().equals(document.id()))
                .orElse(false);
        if (!current && !history) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        }
        return new ReviewDocumentContent(document, storage.open(new StorageKey(document.storageKey())));
    }

    public ReviewSessionOperationResult remove(RemoveReviewDocumentCommand command) {
        validateKey(command.idempotencyKey());
        CurrentUser user = currentUser();
        if (coordinator.readIdempotency(REMOVE_SCOPE, command.idempotencyKey()).isEmpty()) {
            prevalidateRemove(command);
        }
        String fingerprint = ReviewSessionFingerprint.create()
                .field("scope", REMOVE_SCOPE)
                .field("actor", user.userId())
                .field("sessionId", command.sessionId())
                .field("documentId", command.documentId())
                .field("expectedVersion", command.expectedVersion())
                .hex();
        try {
            ReviewSessionOperationResult result = coordinator.remove(command, user, fingerprint, clock.instant());
            flow("REVIEW_DOCUMENT_REMOVE", "APPLICATION", "removeDocument", Map.of(
                    "sessionId", command.sessionId(),
                    "documentId", command.documentId(),
                    "currentVersion", result.session().versionNo(),
                    "replayed", result.replayed()));
            return result;
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            throw conflict("material removal conflicted");
        }
    }

    public ReviewSessionOperationResult confirm(ConfirmReviewMaterialsCommand command) {
        flow("REVIEW_MATERIALS_CONFIRM_START", "APPLICATION", "confirmMaterials", Map.of(
                "sessionId", command.sessionId(),
                "expectedVersion", command.expectedVersion()));
        validateKey(command.idempotencyKey());
        CurrentUser user = currentUser();
        if (coordinator.readIdempotency(CONFIRM_SCOPE, command.idempotencyKey()).isEmpty()) {
            prevalidateConfirm(command);
        }
        String fingerprint = ReviewSessionFingerprint.create()
                .field("scope", CONFIRM_SCOPE)
                .field("actor", user.userId())
                .field("sessionId", command.sessionId())
                .field("expectedVersion", command.expectedVersion())
                .hex();
        try {
            ReviewSessionOperationResult result = coordinator.confirm(command, user, fingerprint, clock.instant());
            flow("REVIEW_MATERIALS_CONFIRM_COMPLETE", "APPLICATION", "confirmMaterials", Map.of(
                    "sessionId", result.session().id(),
                    "sessionStatus", result.session().status(),
                    "currentVersion", result.session().versionNo(),
                    "replayed", result.replayed()));
            return result;
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            throw conflict("materials confirmation conflicted");
        }
    }

    public ReviewSessionOperationResult delete(DeleteReviewSessionCommand command) {
        flow("REVIEW_SESSION_DELETE_START", "APPLICATION", "deleteSession", Map.of(
                "sessionId", command.sessionId(),
                "expectedVersion", command.expectedVersion()));
        validateKey(command.idempotencyKey());
        CurrentUser user = currentUser();
        if (coordinator.readIdempotency(DELETE_SCOPE, command.idempotencyKey()).isEmpty()) {
            ContractReviewSession session = requireSession(command.sessionId());
            requireActiveVersion(session, command.expectedVersion());
            if (!documentRepository.findPendingBySessionId(command.sessionId()).isEmpty()) {
                throw conflict("review session has a pending upload");
            }
        }
        String fingerprint = ReviewSessionFingerprint.create()
                .field("scope", DELETE_SCOPE)
                .field("actor", user.userId())
                .field("sessionId", command.sessionId())
                .field("expectedVersion", command.expectedVersion())
                .hex();
        try {
            ReviewSessionOperationResult result = coordinator.deleteSession(
                    command, user, fingerprint, clock.instant());
            flow("REVIEW_SESSION_DELETE_COMPLETE", "APPLICATION", "deleteSession", Map.of(
                    "sessionId", result.session().id(),
                    "expectedVersion", command.expectedVersion(),
                    "currentVersion", result.session().versionNo(),
                    "replayed", result.replayed()));
            return result;
        } catch (ContractReviewConcurrencyException | IdempotencyConcurrencyException exception) {
            throw conflict("review session deletion conflicted");
        }
    }

    public ReviewDocumentOperationResult recover(
            UUID sessionId, UUID documentId, ReviewRequestMetadata metadata) {
        flow("REVIEW_UPLOAD_RECOVERY_START", "APPLICATION", "recoverUpload", Map.of(
                "sessionId", sessionId,
                "documentId", documentId));
        CurrentUser user = currentUser();
        ContractReviewSession recoverySession = requireSession(sessionId);
        if (recoverySession.deleted()) {
            throw conflict("deleted review session cannot be recovered");
        }
        ReviewDocument document = documentRepository.findBySessionAndId(sessionId, documentId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        IdempotencyRecord idempotency = coordinator.readUploadIdempotency(document.id())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "upload claim is missing"));
        if (idempotency.status() == IdempotencyStatus.SUCCEEDED) {
            flow("REVIEW_UPLOAD_RECOVERY_TERMINAL_REPLAY", "APPLICATION", "recoverUpload", Map.of(
                    "sessionId", sessionId,
                    "documentId", documentId,
                    "replayed", true,
                    "recoveryDecision", "SUCCEEDED"));
            return coordinator.replayUploadResult(idempotency);
        }
        if (idempotency.status() == IdempotencyStatus.FAILED
                || document.status() == ReviewDocumentStatus.UPLOAD_FAILED) {
            throw conflict("upload previously failed; use a new Idempotency-Key");
        }
        if (document.status() != ReviewDocumentStatus.UPLOAD_PENDING) {
            throw conflict("document is not recoverable");
        }
        MaterialBinding binding = bindingRepository.findBySessionAndSlot(sessionId, document.slotKey()).orElse(null);
        ReviewDocumentUploadStart start = ReviewDocumentUploadStart.executing(idempotency, document, binding);
        Instant now = clock.instant();
        boolean stale = !document.updatedAt().plus(recoveryAge).isAfter(now);
        ReviewRequestMetadata requiredMetadata = requireMetadata(metadata);
        StoredFileInspection inspection = storage.inspect(new StorageKey(document.storageKey()));
        switch (inspection.status()) {
            case PRESENT -> {
                if (inspection.file().size() == document.fileSize()
                        && inspection.file().sha256().equals(document.sha256())) {
                    flow("REVIEW_UPLOAD_RECOVERY_PRESENT_MATCH", "APPLICATION", "recoverUpload", Map.of(
                            "sessionId", sessionId,
                            "documentId", documentId,
                            "fileSize", inspection.file().size(),
                            "recoveryDecision", "COMPLETE"));
                    try {
                        flow("REVIEW_UPLOAD_COMPLETE_TX_ENTER", "TRANSACTION", "completeUpload", Map.of(
                                "sessionId", sessionId,
                                "documentId", documentId,
                                "reservedVersion", document.reservedSessionVersion()));
                        ReviewDocumentOperationResult result = coordinator.completeUpload(
                                start, user, requiredMetadata, now);
                        flow("REVIEW_UPLOAD_COMPLETE_TX_COMMIT", "TRANSACTION", "completeUpload", Map.of(
                                "sessionId", result.session().id(),
                                "documentId", result.document().id(),
                                "documentStatus", result.document().status(),
                                "currentVersion", result.session().versionNo(),
                                "replayed", result.replayed()));
                        return result;
                    } catch (RuntimeException exception) {
                        return resolveCompleteOutcome(start, idempotency.idempotencyKey());
                    }
                }
                flow("REVIEW_UPLOAD_RECOVERY_PRESENT_CONFLICT", "APPLICATION", "recoverUpload", Map.of(
                        "sessionId", sessionId,
                        "documentId", documentId,
                        "recoveryDecision", "FAIL_INTEGRITY_CONFLICT"));
                closeUploadFailure(start, user, requiredMetadata,
                        new FileStorageContentConflictException("stable object metadata conflict"));
            }
            case DEFINITELY_MISSING -> {
                if (!stale) {
                    flow("REVIEW_UPLOAD_RECOVERY_MISSING_NOT_STALE", "APPLICATION", "recoverUpload", Map.of(
                            "sessionId", sessionId,
                            "documentId", documentId,
                            "recoveryDecision", "KEEP_IN_PROGRESS"));
                    throw conflict("upload is in progress");
                }
                flow("REVIEW_UPLOAD_RECOVERY_MISSING_STALE", "APPLICATION", "recoverUpload", Map.of(
                        "sessionId", sessionId,
                        "documentId", documentId,
                        "recoveryDecision", "FAIL_DEFINITELY_MISSING"));
                closeUploadFailure(start, user, requiredMetadata,
                        new FileStorageException("stable object is definitely missing"));
            }
            case UNKNOWN -> {
                flow("REVIEW_UPLOAD_RECOVERY_UNCERTAIN", "APPLICATION", "recoverUpload", Map.of(
                        "sessionId", sessionId,
                        "documentId", documentId,
                        "documentKind", document.documentKind(),
                        "recoveryDecision", "UNCERTAIN",
                        "storageReason", inspection.safeReason()));
                throw conflict("upload storage state is uncertain; retry recovery later");
            }
        }
        ReviewDocument failed = documentRepository.findById(document.id())
                .orElseThrow(() -> new IllegalStateException("failed upload document is missing"));
        if (failed.status() != ReviewDocumentStatus.UPLOAD_FAILED) {
            throw conflict("upload failure closure is uncertain; retry recovery later");
        }
        return new ReviewDocumentOperationResult(failed, null, requireSession(sessionId), false);
    }

    private String uploadFingerprint(
            UploadReviewDocumentCommand command, CurrentUser user, ValidatedReviewFile file) {
        return ReviewSessionFingerprint.create()
                .field("scope", UPLOAD_SCOPE)
                .field("actor", user.userId())
                .field("sessionId", command.sessionId())
                .field("expectedVersion", command.expectedVersion())
                .field("documentKind", command.documentKind())
                .field("slotSemantics", command.documentKind().singleton() ? "SINGLETON_REPLACE" : "MULTI_NEW")
                .field("safeFileName", file.safeFileName())
                .field("sha256", file.stagedFile().sha256())
                .field("size", file.stagedFile().size())
                .field("contentType", file.canonicalContentType())
                .hex();
    }

    private ReviewDocumentOperationResult resolveCompleteOutcome(
            ReviewDocumentUploadStart start, String idempotencyKey) {
        IdempotencyRecord current;
        try {
            current = coordinator.readIdempotency(UPLOAD_SCOPE, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("durable upload claim is missing"));
        } catch (RuntimeException uncertain) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "upload outcome is uncertain");
        }
        if (current.status() == IdempotencyStatus.SUCCEEDED) {
            return coordinator.replayUploadResult(current);
        }
        if (current.status() == IdempotencyStatus.FAILED) {
            throw conflict("document upload previously failed; use a new Idempotency-Key");
        }
        throw conflict("document upload is in progress");
    }

    private void closeUploadFailure(
            ReviewDocumentUploadStart start,
            CurrentUser user,
            ReviewRequestMetadata metadata,
            RuntimeException original) {
        try {
            flow("REVIEW_UPLOAD_FAILURE_TX_ENTER", "TRANSACTION", "failUpload", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "reservedVersion", start.document().reservedSessionVersion()));
            coordinator.failUpload(start, user, metadata, original.getClass().getSimpleName(), clock.instant());
            flow("REVIEW_UPLOAD_FAILURE_TX_COMMIT", "TRANSACTION", "failUpload", Map.of(
                    "sessionId", start.document().sessionId(),
                    "documentId", start.document().id(),
                    "documentStatus", ReviewDocumentStatus.UPLOAD_FAILED));
        } catch (RuntimeException closureFailure) {
            original.addSuppressed(closureFailure);
        }
    }

    private void requireUploadCandidate(UUID sessionId, long expectedVersion) {
        ContractReviewSession session = requireSession(sessionId);
        requirePreparingVersion(session, expectedVersion);
        if (!documentRepository.findPendingBySessionId(sessionId).isEmpty()) {
            throw conflict("review session has a pending upload");
        }
    }

    private void prevalidateRemove(RemoveReviewDocumentCommand command) {
        ContractReviewSession session = requireSession(command.sessionId());
        requirePreparingVersion(session, command.expectedVersion());
        if (!documentRepository.findPendingBySessionId(command.sessionId()).isEmpty()) {
            throw conflict("review session has a pending upload");
        }
        ReviewDocument document = documentRepository.findBySessionAndId(command.sessionId(), command.documentId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        boolean current = bindingRepository.findBySessionAndSlot(command.sessionId(), document.slotKey())
                .filter(MaterialBinding::active)
                .map(binding -> binding.boundDocumentId().equals(document.id()))
                .orElse(false);
        if (!current) {
            throw conflict("document is not current");
        }
    }

    private void prevalidateConfirm(ConfirmReviewMaterialsCommand command) {
        ContractReviewSession session = requireSession(command.sessionId());
        requirePreparingVersion(session, command.expectedVersion());
        if (!documentRepository.findPendingBySessionId(command.sessionId()).isEmpty()) {
            throw conflict("review session has a pending upload");
        }
        coordinator.requireCoreMaterials(command.sessionId());
    }

    private ContractReviewSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
    }

    private void requirePreparingVersion(ContractReviewSession session, long expectedVersion) {
        requireLegacySource(session);
        if (session.deleted()
                || session.status() != ReviewSessionStatus.PREPARING_MATERIALS
                || session.versionNo() != expectedVersion) {
            throw conflict("review session status or version changed");
        }
    }

    private void requireActiveVersion(ContractReviewSession session, long expectedVersion) {
        if (session.deleted() || session.versionNo() != expectedVersion) {
            throw conflict("review session status or version changed");
        }
    }

    private void requireLegacySource(ContractReviewSession session) {
        if (session.sourceKind() != com.fuquan.audit.contract.domain.ReviewSessionSourceKind.LEGACY_REVIEW_DOCUMENT) {
            throw conflict("Use the Project file workflow for this review session");
        }
    }

    private CurrentUser currentUser() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private ReviewRequestMetadata requireMetadata(ReviewRequestMetadata metadata) {
        if (metadata == null
                || metadata.requestId() == null || metadata.requestId().isBlank()
                || metadata.traceId() == null || metadata.traceId().isBlank()) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "request trace metadata is unavailable");
        }
        return metadata;
    }

    private void validateKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    private void flow(
            String event,
            String layer,
            String operation,
            Map<String, ?> fields) {
        flowDebugLogger.log(event, "CONTRACT", layer, operation, fields);
    }

    private void logStorageFailure(
            String stage,
            ReviewRequestMetadata metadata,
            UUID sessionId,
            UUID documentId,
            Throwable exception) {
        SafeExceptionDiagnostics.SafeStackFrame frame =
                SafeExceptionDiagnostics.safeTopStackFrame(exception);
        log.error(
            "requestId={} traceId={} module=CONTRACT layer=STORAGE stage={} "
                        + "operation=uploadDocument result=error sessionId={} documentId={} "
                        + "safeReason={} wrapperExceptionType={} rootCauseType={} "
                        + "topStackClass={} topStackMethod={} topStackLine={}",
                metadata.requestId(),
                metadata.traceId(),
                stage,
                sessionId,
                documentId,
                storageReason(exception),
                exception.getClass().getName(),
                SafeExceptionDiagnostics.findRootCauseType(exception),
                frame.className(),
                frame.methodName(),
                frame.lineNumber());
    }

    private static StorageFailureReason storageReason(Throwable exception) {
        if (exception instanceof FileStorageException storageException) {
            return storageException.reason();
        }
        if (exception instanceof java.nio.file.AccessDeniedException) {
            return StorageFailureReason.ACCESS_DENIED;
        }
        if (exception instanceof java.nio.file.FileSystemException) {
            return StorageFailureReason.FILESYSTEM_ERROR;
        }
        if (exception instanceof IOException) {
            return StorageFailureReason.IO_ERROR;
        }
        return StorageFailureReason.PROVIDER_ERROR;
    }
}
