package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStorageContentConflictException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import com.fuquan.audit.contract.domain.ReviewDocumentStatus;
import com.fuquan.audit.contract.infra.memory.InMemoryContractReviewSessionRepository;
import com.fuquan.audit.contract.infra.memory.InMemoryContractReviewStore;
import com.fuquan.audit.contract.infra.memory.InMemoryMaterialBindingRepository;
import com.fuquan.audit.contract.infra.memory.InMemoryReviewDocumentRepository;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ContractReviewUploadRecoveryTest {
    private static final byte[] PDF = "%PDF-recovery".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final ReviewRequestMetadata META = new ReviewRequestMetadata("request", "a".repeat(32), "127.0.0.1");
    private static final CurrentUser USER = new CurrentUser("user", "user", "User", Set.of("AUDITOR"));

    @TempDir
    Path tempDir;

    private InMemoryContractReviewStore store;
    private InMemoryContractReviewSessionRepository sessions;
    private InMemoryReviewDocumentRepository documents;
    private InMemoryMaterialBindingRepository bindings;
    private InMemoryIdempotencyAdapter idempotency;
    private FileSystemStorageAdapter storage;
    private ReviewFileValidator validator;
    private ContractReviewSessionTransactionCoordinator coordinator;
    private ContractReviewSessionService service;
    private List<OperationAuditEvent> audits;
    private FlowDebugLogger flowDebugLogger;
    private Contract contract;

    @BeforeEach
    void setUp() {
        store = new InMemoryContractReviewStore();
        sessions = new InMemoryContractReviewSessionRepository(store);
        documents = new InMemoryReviewDocumentRepository(store);
        bindings = new InMemoryMaterialBindingRepository(store);
        idempotency = new InMemoryIdempotencyAdapter();
        storage = new FileSystemStorageAdapter(
                tempDir.resolve("storage"),
                (source, target) -> Files.createLink(target, source));
        validator = new ReviewFileValidator();
        audits = new ArrayList<>();
        flowDebugLogger = mock(FlowDebugLogger.class);
        coordinator = new ContractReviewSessionTransactionCoordinator(
                sessions, documents, bindings, idempotency, audits::add);
        contract = contract();
        service = new ContractReviewSessionService(
                id -> id.equals(contract.id()) ? Optional.of(contract) : Optional.empty(),
                sessions, documents, bindings, storage, validator, coordinator,
                () -> Optional.of(USER), Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                new ReviewFilePolicy(50L * 1024 * 1024, Duration.ofSeconds(10)),
                flowDebugLogger);
    }

    @Test
    void startCommitWithoutStableObjectRecoversToFailedAndReleasesReservation() {
        ReviewDocumentUploadStart start = start("missing-object");

        ReviewDocumentOperationResult result = service.recover(
                start.document().sessionId(), start.document().id(), META);

        assertThat(result.document().status()).isEqualTo(ReviewDocumentStatus.UPLOAD_FAILED);
        assertThat(result.session().versionNo()).isEqualTo(2);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "missing-object")).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(audits).extracting(OperationAuditEvent::eventType)
                .contains("REVIEW_DOCUMENT_UPLOAD_STARTED", "REVIEW_DOCUMENT_UPLOAD_FAILED");
        OperationAuditEvent failure = audit("REVIEW_DOCUMENT_UPLOAD_FAILED");
        assertDocumentAudit(failure, start.document().id(), start.document().sessionId());
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_RECOVERY_MISSING_STALE"), eq("CONTRACT"),
                eq("APPLICATION"), eq("recoverUpload"), anyMap());
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_FAILURE_TX_COMMIT"), eq("CONTRACT"),
                eq("TRANSACTION"), eq("failUpload"), anyMap());
    }

    @Test
    void definitelyMissingButNotStaleRemainsPendingAndInProgress() {
        ReviewDocumentUploadStart start = start("missing-not-stale");
        ContractReviewSessionService nonStaleService = serviceWith(
                storage, coordinator, Duration.ofHours(1));

        assertThatThrownBy(() -> nonStaleService.recover(
                start.document().sessionId(), start.document().id(), META))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("in progress");

        assertThat(documents.findById(start.document().id())).get()
                .extracting(ReviewDocument::status).isEqualTo(ReviewDocumentStatus.UPLOAD_PENDING);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "missing-not-stale")).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_RECOVERY_MISSING_NOT_STALE"), eq("CONTRACT"),
                eq("APPLICATION"), eq("recoverUpload"), anyMap());
    }

    @Test
    void promotedObjectRecoversToAvailableWithoutOriginalBrowserKey() {
        ReviewDocumentUploadStart start = start("promoted-object");
        storage.promote(stagedCopy(), new StorageKey(start.document().storageKey()));

        ReviewDocumentOperationResult result = service.recover(
                start.document().sessionId(), start.document().id(), META);

        assertThat(result.document().status()).isEqualTo(ReviewDocumentStatus.AVAILABLE);
        assertThat(result.session().versionNo()).isEqualTo(2);
        assertThat(result.currentBinding().boundDocumentId()).isEqualTo(start.document().id());
        assertThat(service.recover(start.document().sessionId(), start.document().id(), META).replayed()).isTrue();
        assertDocumentAudit(
                audit("REVIEW_DOCUMENT_ATTACHED"), start.document().id(), start.document().sessionId());
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_RECOVERY_PRESENT_MATCH"), eq("CONTRACT"),
                eq("APPLICATION"), eq("recoverUpload"), anyMap());
    }

    @Test
    void sameKeyRetryAfterStartAndPromoteCompletesExistingDocumentWithoutSecondReservation() {
        String key = "same-key";
        ReviewDocumentUploadStart start = start(key);
        storage.promote(stagedCopy(), new StorageKey(start.document().storageKey()));

        ReviewDocumentOperationResult result = service.upload(new UploadReviewDocumentCommand(
                start.document().sessionId(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF), key, META));

        assertThat(result.document().id()).isEqualTo(start.document().id());
        assertThat(result.document().status()).isEqualTo(ReviewDocumentStatus.AVAILABLE);
        assertThat(result.session().versionNo()).isEqualTo(2);
        assertThat(documents.findBySessionId(start.document().sessionId())).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentFileIsConflictAndCannotOverwriteStableObject() {
        String key = "different-file";
        ReviewDocumentUploadStart start = start(key);
        storage.promote(stagedCopy(), new StorageKey(start.document().storageKey()));

        assertThatThrownBy(() -> service.upload(new UploadReviewDocumentCommand(
                start.document().sessionId(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-other".getBytes(StandardCharsets.US_ASCII)), key, META)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fingerprint conflict");
        assertThat(storage.inspect(new StorageKey(start.document().storageKey())).file().sha256())
                .isEqualTo(start.document().sha256());
    }

    @Test
    void unknownStorageStateLeavesDurableOperationInProgress() {
        ReviewDocumentUploadStart start = start("unknown-storage");
        ContractReviewSessionService uncertainService = serviceWithStorage(
                new InspectFailureStorage(storage, StorageFailureReason.ACCESS_DENIED));

        assertThatThrownBy(() -> uncertainService.recover(
                start.document().sessionId(), start.document().id(), META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("uncertain");
        assertThat(documents.findById(start.document().id())).get()
                .extracting(document -> document.status()).isEqualTo(ReviewDocumentStatus.UPLOAD_PENDING);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "unknown-storage")).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_RECOVERY_UNCERTAIN"), eq("CONTRACT"),
                eq("APPLICATION"), eq("recoverUpload"), argThat(fields ->
                        fields.get("storageReason") == StorageFailureReason.ACCESS_DENIED
                                && fields.get("recoveryDecision").equals("UNCERTAIN")));
    }

    @Test
    void staleIoUnknownAlsoRemainsPendingAndInProgress() {
        ReviewDocumentUploadStart start = start("unknown-storage-io");
        ContractReviewSessionService uncertainService = serviceWithStorage(
                new InspectFailureStorage(storage, StorageFailureReason.IO_ERROR));

        assertThatThrownBy(() -> uncertainService.recover(
                start.document().sessionId(), start.document().id(), META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("uncertain");

        assertThat(documents.findById(start.document().id())).get()
                .extracting(ReviewDocument::status).isEqualTo(ReviewDocumentStatus.UPLOAD_PENDING);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "unknown-storage-io")).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        verify(flowDebugLogger).log(
                eq("REVIEW_UPLOAD_RECOVERY_UNCERTAIN"), eq("CONTRACT"),
                eq("APPLICATION"), eq("recoverUpload"), argThat(fields ->
                        fields.get("storageReason") == StorageFailureReason.IO_ERROR));
    }

    @Test
    void sessionAndDocumentAuditsUseTrueResourceIdentityAndNeverTaskId() {
        ReviewSessionOperationResult created = service.create(new CreateReviewSessionCommand(
                contract.id(), "FixB1 session", null, "audit-session", META));
        ReviewDocumentOperationResult attached = service.upload(new UploadReviewDocumentCommand(
                created.session().id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "audit-upload", META));
        service.remove(new RemoveReviewDocumentCommand(
                created.session().id(), attached.document().id(), attached.session().versionNo(),
                "audit-remove", META));

        OperationAuditEvent sessionEvent = audit("REVIEW_SESSION_CREATED");
        assertThat(sessionEvent.resourceType()).isEqualTo("CONTRACT_REVIEW_SESSION");
        assertThat(sessionEvent.resourceId()).isEqualTo(created.session().id().toString());
        assertThat(sessionEvent.taskId()).isNull();

        for (String eventType : List.of(
                "REVIEW_DOCUMENT_UPLOAD_STARTED",
                "REVIEW_DOCUMENT_ATTACHED",
                "REVIEW_DOCUMENT_REMOVED")) {
            assertDocumentAudit(audit(eventType), attached.document().id(), created.session().id());
        }
        assertThat(audits).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo(META.requestId());
            assertThat(event.traceId()).isEqualTo(META.traceId());
            assertThat(event.taskId()).isNull();
            if (event.detail() != null) {
                assertThat(event.detail()).doesNotContain("audit-upload", "\\storage\\", "/storage/");
            }
        });
        verify(flowDebugLogger, atLeastOnce()).log(
                eq("REVIEW_UPLOAD_STAGE_START"), eq("CONTRACT"),
                eq("STORAGE"), eq("uploadDocument"), anyMap());
        verify(flowDebugLogger, atLeastOnce()).log(
                eq("REVIEW_UPLOAD_PUBLISH_COMPLETE"), eq("CONTRACT"),
                eq("STORAGE"), eq("publishDocument"), anyMap());
        verify(flowDebugLogger, atLeastOnce()).log(
                eq("REVIEW_UPLOAD_COMPLETE_TX_COMMIT"), eq("CONTRACT"),
                eq("TRANSACTION"), eq("completeUpload"), anyMap());
    }

    @Test
    void recoveryAgeMustBeStrictlyPositive() {
        assertThatThrownBy(() -> new ReviewFilePolicy(1024, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new ReviewFilePolicy(1024, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void userFileRejectionStorageFailureAndContentConflictHaveDistinctErrorCodes() {
        ContractReviewSession emptySession = newSession();
        assertThatThrownBy(() -> service.upload(new UploadReviewDocumentCommand(
                emptySession.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "empty.pdf", "application/pdf", new ByteArrayInputStream(new byte[0]),
                "empty-file", META)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.BUSINESS_VALIDATION_FAILED));

        ContractReviewSession largeSession = newSession();
        ContractReviewSessionService smallLimitService = serviceWith(
                storage, coordinator, Duration.ofSeconds(10), 5);
        assertThatThrownBy(() -> smallLimitService.upload(new UploadReviewDocumentCommand(
                largeSession.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "large.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "large-file", META)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.PAYLOAD_TOO_LARGE));

        ContractReviewSession ioSession = newSession();
        ContractReviewSessionService ioFailureService = serviceWithStorage(new StageFailureStorage(storage));
        assertThatThrownBy(() -> ioFailureService.upload(new UploadReviewDocumentCommand(
                ioSession.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "stage-io", META)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.INTERNAL_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("file storage is temporarily unavailable");
                    assertThat(exception.getMessage()).doesNotContain("D:\\private");
                });

        ContractReviewSession permissionSession = newSession();
        ContractReviewSessionService permissionFailureService = serviceWithStorage(
                new PermissionFailureStorage(storage));
        assertThatThrownBy(() -> permissionFailureService.upload(new UploadReviewDocumentCommand(
                permissionSession.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "stage-permission", META)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.INTERNAL_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("file storage is temporarily unavailable");
                });

        ContractReviewSession conflictSession = newSession();
        ContractReviewSessionService conflictService = serviceWithStorage(new ConflictPromoteStorage(storage));
        assertThatThrownBy(() -> conflictService.upload(new UploadReviewDocumentCommand(
                conflictSession.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "content-conflict", META)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
        verify(flowDebugLogger, atLeastOnce()).log(
                eq("REVIEW_UPLOAD_PUBLISH_FAILED"), eq("CONTRACT"),
                eq("STORAGE"), eq("publishDocument"), anyMap());
    }

    @Test
    void storageTechnicalLogIncludesSafeDiagnosticsButNeverRawExceptionPath(CapturedOutput output) {
        ContractReviewSession session = newSession();
        ContractReviewSessionService ioFailureService = serviceWithStorage(new StageFailureStorage(storage));

        assertThatThrownBy(() -> ioFailureService.upload(new UploadReviewDocumentCommand(
                session.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "safe-storage-log", META)))
                .isInstanceOf(BusinessException.class);

        assertThat(output.getOut() + output.getErr())
                .contains("safeReason=IO_ERROR")
                .contains("wrapperExceptionType=" + FileStorageException.class.getName())
                .contains("rootCauseType=" + IOException.class.getName())
                .contains("topStackClass=")
                .doesNotContain("C:\\SECRET\\contract.pdf")
                .doesNotContain("/secret/storage/contract.pdf");
    }

    @Test
    void committedFailureResponseLossIsTerminalAndRequiresNewKey() {
        ReviewDocumentUploadStart start = start("failed-response-lost");
        coordinator.failUpload(start, USER, META, "TestFailure", NOW.plusSeconds(5));

        assertThatThrownBy(() -> service.upload(new UploadReviewDocumentCommand(
                start.document().sessionId(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "failed-response-lost", META)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("previously failed");
    }

    @Test
    void completeCommitResponseLossReplaysSucceededInsteadOfMarkingFailed() {
        ReviewDocumentUploadStart start = start("complete-response-lost");
        ContractReviewSessionTransactionCoordinator responseLost = new ResponseLostCoordinator(
                sessions, documents, bindings, idempotency, audits);
        ContractReviewSessionService responseLostService = serviceWith(storage, responseLost);

        ReviewDocumentOperationResult result = responseLostService.upload(new UploadReviewDocumentCommand(
                start.document().sessionId(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "complete-response-lost", META));

        assertThat(result.replayed()).isTrue();
        assertThat(result.document().status()).isEqualTo(ReviewDocumentStatus.AVAILABLE);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "complete-response-lost")).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(audits).extracting(OperationAuditEvent::eventType)
                .doesNotContain("REVIEW_DOCUMENT_UPLOAD_FAILED");
    }

    @Test
    void failureClosureUnavailableLeavesPendingAndInProgressForRecovery() {
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), contract.id(), contract.versionNo(), "审核", "F02_DETERMINISTIC",
                null, USER.userId(), NOW);
        sessions.create(session);
        ContractReviewSessionTransactionCoordinator unavailable = new FailureUnavailableCoordinator(
                sessions, documents, bindings, idempotency, audits);
        ContractReviewSessionService unavailableService = serviceWith(
                new PromoteFailureStorage(storage), unavailable);

        assertThatThrownBy(() -> unavailableService.upload(new UploadReviewDocumentCommand(
                session.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF),
                "failure-db-unavailable", META)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("temporarily unavailable");

        assertThat(documents.findPendingBySessionId(session.id())).hasSize(1);
        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.UPLOAD_SCOPE, "failure-db-unavailable")).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void recoveryAndSameKeyRetryRaceHasOneDocumentAndNeverOverwritesSucceeded() throws Exception {
        String key = "recovery-retry-race";
        ReviewDocumentUploadStart start = start(key);
        storage.promote(stagedCopy(), new StorageKey(start.document().storageKey()));
        CountDownLatch gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> recovery = executor.submit(() -> afterGate(gate, () ->
                    service.recover(start.document().sessionId(), start.document().id(), META)));
            Future<Object> retry = executor.submit(() -> afterGate(gate, () -> service.upload(
                    new UploadReviewDocumentCommand(
                            start.document().sessionId(), ReviewDocumentKind.CONTRACT_BODY, 0,
                            "contract.pdf", "application/pdf", new ByteArrayInputStream(PDF), key, META))));
            gate.countDown();
            Object first = recovery.get();
            Object second = retry.get();

            assertThat(List.of(first, second)).anySatisfy(value ->
                    assertThat(value).isInstanceOf(ReviewDocumentOperationResult.class));
        } finally {
            executor.shutdownNow();
        }
        assertThat(documents.findBySessionId(start.document().sessionId())).hasSize(1)
                .allSatisfy(document -> assertThat(document.status()).isEqualTo(ReviewDocumentStatus.AVAILABLE));
        assertThat(bindings.findBySessionId(start.document().sessionId())).hasSize(1);
        assertThat(idempotency.findByScopeAndKey(ContractReviewSessionService.UPLOAD_SCOPE, key)).get()
                .extracting(record -> record.status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
    }

    private ReviewDocumentUploadStart start(String key) {
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), contract.id(), contract.versionNo(), "审核", "F02_DETERMINISTIC",
                null, USER.userId(), NOW);
        sessions.create(session);
        StagedFile staged = stagedCopy();
        ValidatedReviewFile file = validator.validate(staged, "contract.pdf", "application/pdf");
        String fingerprint = ReviewSessionFingerprint.create()
                .field("scope", ContractReviewSessionService.UPLOAD_SCOPE)
                .field("actor", USER.userId())
                .field("sessionId", session.id())
                .field("expectedVersion", 0)
                .field("documentKind", ReviewDocumentKind.CONTRACT_BODY)
                .field("slotSemantics", "SINGLETON_REPLACE")
                .field("safeFileName", "contract.pdf")
                .field("sha256", staged.sha256())
                .field("size", staged.size())
                .field("contentType", "application/pdf")
                .hex();
        StorageKey storageKey = new StorageKey(ReviewSessionFingerprint.create()
                .field("scope", ContractReviewSessionService.UPLOAD_SCOPE)
                .field("actor", USER.userId())
                .field("sessionId", session.id())
                .field("idempotencyKey", key)
                .hex());
        ReviewDocumentUploadStart start = coordinator.startUpload(
                UUID.randomUUID(), session.id(), ReviewDocumentKind.CONTRACT_BODY, 0,
                file, storageKey, USER, key, fingerprint, META, NOW.plusSeconds(1));
        storage.discard(staged);
        return start;
    }

    private StagedFile stagedCopy() {
        return storage.stage(new ByteArrayInputStream(PDF), 1024);
    }

    private ContractReviewSessionService serviceWithStorage(FileStoragePort fileStorage) {
        return serviceWith(fileStorage, coordinator);
    }

    private ContractReviewSessionService serviceWith(
            FileStoragePort fileStorage, ContractReviewSessionTransactionCoordinator transactionCoordinator) {
        return serviceWith(fileStorage, transactionCoordinator, Duration.ofSeconds(10));
    }

    private ContractReviewSessionService serviceWith(
            FileStoragePort fileStorage,
            ContractReviewSessionTransactionCoordinator transactionCoordinator,
            Duration recoveryAge) {
        return serviceWith(fileStorage, transactionCoordinator, recoveryAge, 50L * 1024 * 1024);
    }

    private ContractReviewSessionService serviceWith(
            FileStoragePort fileStorage,
            ContractReviewSessionTransactionCoordinator transactionCoordinator,
            Duration recoveryAge,
            long sizeLimit) {
        return new ContractReviewSessionService(
                id -> Optional.of(contract), sessions, documents, bindings, fileStorage,
                validator, transactionCoordinator, () -> Optional.of(USER),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                new ReviewFilePolicy(sizeLimit, recoveryAge),
                flowDebugLogger);
    }

    private ContractReviewSession newSession() {
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), contract.id(), contract.versionNo(), "审核", "F02_DETERMINISTIC",
                null, USER.userId(), NOW);
        sessions.create(session);
        return session;
    }

    private OperationAuditEvent audit(String eventType) {
        return audits.stream()
                .filter(event -> event.eventType().equals(eventType))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void assertDocumentAudit(OperationAuditEvent event, UUID documentId, UUID sessionId) {
        assertThat(event.resourceType()).isEqualTo("CONTRACT_REVIEW_DOCUMENT");
        assertThat(event.resourceId()).isEqualTo(documentId.toString());
        assertThat(event.taskId()).isNull();
        assertThat(event.detail()).contains("sessionId=" + sessionId);
    }

    private Object afterGate(CountDownLatch gate, java.util.concurrent.Callable<Object> action) throws Exception {
        gate.await();
        try {
            return action.call();
        } catch (BusinessException conflict) {
            return conflict;
        }
    }

    private Contract contract() {
        UUID id = UUID.randomUUID();
        return new Contract(
                id, "CONTRACT-1", "Contract", "PROCUREMENT", "Buyer", "Supplier", null,
                Money.of(BigDecimal.TEN), "人民币壹拾元整", "READY", 3, NOW, NOW,
                List.of(), new AwardNoticeReference(
                        UUID.randomUUID(), id, "AWARD-1", "Project", "Supplier", null,
                        Money.of(BigDecimal.TEN), java.time.LocalDate.of(2026, 8, 1), null));
    }

    private record InspectFailureStorage(
            FileStoragePort delegate, StorageFailureReason reason) implements FileStoragePort {
        @Override public StagedFile stage(InputStream input, long limit) { return delegate.stage(input, limit); }
        @Override public void promote(StagedFile staged, StorageKey key) { delegate.promote(staged, key); }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) {
            return StoredFileInspection.unknown(reason);
        }
    }

    private record PromoteFailureStorage(FileStoragePort delegate) implements FileStoragePort {
        @Override public StagedFile stage(InputStream input, long limit) { return delegate.stage(input, limit); }
        @Override public void promote(StagedFile staged, StorageKey key) {
            throw new FileStorageException("promote unavailable");
        }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { return delegate.inspect(key); }
    }

    private record StageFailureStorage(FileStoragePort delegate) implements FileStoragePort {
        @Override public StagedFile stage(InputStream input, long limit) {
            throw new FileStorageException(
                    StorageFailureReason.IO_ERROR,
                    "file staging failed",
                    new IOException("C:\\SECRET\\contract.pdf"));
        }
        @Override public void promote(StagedFile staged, StorageKey key) { delegate.promote(staged, key); }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { return delegate.inspect(key); }
    }

    private record PermissionFailureStorage(FileStoragePort delegate) implements FileStoragePort {
        @Override public StagedFile stage(InputStream input, long limit) {
            throw new FileStorageException(
                    StorageFailureReason.ACCESS_DENIED,
                    "file staging failed",
                    new AccessDeniedException("/secret/storage/contract.pdf"));
        }
        @Override public void promote(StagedFile staged, StorageKey key) { delegate.promote(staged, key); }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { return delegate.inspect(key); }
    }

    private record ConflictPromoteStorage(FileStoragePort delegate) implements FileStoragePort {
        @Override public StagedFile stage(InputStream input, long limit) { return delegate.stage(input, limit); }
        @Override public void promote(StagedFile staged, StorageKey key) {
            throw new FileStorageContentConflictException("stable content differs");
        }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { return delegate.inspect(key); }
    }

    private static class ResponseLostCoordinator extends ContractReviewSessionTransactionCoordinator {
        ResponseLostCoordinator(
                InMemoryContractReviewSessionRepository sessions,
                InMemoryReviewDocumentRepository documents,
                InMemoryMaterialBindingRepository bindings,
                InMemoryIdempotencyAdapter idempotency,
                List<OperationAuditEvent> audits) {
            super(sessions, documents, bindings, idempotency, audits::add);
        }

        @Override
        public ReviewDocumentOperationResult completeUpload(
                ReviewDocumentUploadStart start, CurrentUser user, ReviewRequestMetadata metadata, Instant now) {
            super.completeUpload(start, user, metadata, now);
            throw new IllegalStateException("response lost after commit");
        }
    }

    private static class FailureUnavailableCoordinator extends ContractReviewSessionTransactionCoordinator {
        FailureUnavailableCoordinator(
                InMemoryContractReviewSessionRepository sessions,
                InMemoryReviewDocumentRepository documents,
                InMemoryMaterialBindingRepository bindings,
                InMemoryIdempotencyAdapter idempotency,
                List<OperationAuditEvent> audits) {
            super(sessions, documents, bindings, idempotency, audits::add);
        }

        @Override
        public void failUpload(
                ReviewDocumentUploadStart start, CurrentUser user, ReviewRequestMetadata metadata,
                String safeFailureType, Instant now) {
            throw new IllegalStateException("database unavailable");
        }
    }
}
