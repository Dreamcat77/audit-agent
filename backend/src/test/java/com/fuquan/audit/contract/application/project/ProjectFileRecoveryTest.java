package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class ProjectFileRecoveryTest {
    @TempDir Path root;

    @Test
    void succeededReplaysAndTerminalPublicationFailureBecomesDurableFailed() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var completed = support.ingestion.upload(support.command("success"));
        assertThat(support.recovery(support.durableStorage).recover(
                support.project.id(), completed.id(), ProjectRecoveryTestSupport.META).id()).isEqualTo(completed.id());

        var terminalStorage = new ProjectRecoveryTestSupport.StorageDecorator(support.durableStorage) {
            @Override public void promote(com.fuquan.audit.contract.application.port.StagedFile staged, StorageKey key) {
                throw new FileStorageException(StorageFailureReason.PUBLICATION_UNSUPPORTED, "unsupported");
            }
        };
        assertThatThrownBy(() -> support.ingestion(terminalStorage).upload(support.command("terminal")))
                .isInstanceOf(BusinessException.class);
        var failed = support.repositories.projectFileRepository().findByProjectId(support.project.id()).stream()
                .filter(file -> !file.id().equals(completed.id())).findFirst().orElseThrow();
        assertThat(failed.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.UPLOAD_FAILED);
        assertThat(support.record(failed.id()).status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThatThrownBy(() -> support.recovery(support.durableStorage).recover(
                support.project.id(), failed.id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("previously failed");
    }

    @Test
    void sameKeyRetryAfterTerminalFailedUploadReturnsStableConflict() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var terminalStorage = new ProjectRecoveryTestSupport.StorageDecorator(support.durableStorage) {
            @Override public void promote(com.fuquan.audit.contract.application.port.StagedFile staged, StorageKey key) {
                throw new FileStorageException(StorageFailureReason.PUBLICATION_UNSUPPORTED, "unsupported");
            }
        };

        assertThatThrownBy(() -> support.ingestion(terminalStorage).upload(support.command("failed-retry")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("upload failed");
        assertThatThrownBy(() -> support.ingestion.upload(support.command("failed-retry")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("previously failed");

        assertThat(support.idempotency.size()).isEqualTo(1);
        assertThat(support.repositories.projectFileRepository().findByProjectId(support.project.id()))
                .singleElement().satisfies(file -> {
                    assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.UPLOAD_FAILED);
                    assertThat(support.record(file.id()).status()).isEqualTo(IdempotencyStatus.FAILED);
                });
    }

    @Test
    void pendingPresentCompletesWhileMissingUsesAgeAndUnknownRemainsPending() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var present = support.pending(true, false, null, null);
        assertThat(support.recovery(support.durableStorage).recover(support.project.id(), present.file().id(),
                ProjectRecoveryTestSupport.META).ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);

        var freshMissing = support.pending(false, false, null, null);
        assertThatThrownBy(() -> support.recovery(support.durableStorage).recover(
                support.project.id(), freshMissing.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("in progress");
        assertPending(support, freshMissing.file().id());

        var staleMissing = support.pending(false, true, null, null);
        assertThatThrownBy(() -> support.recovery(support.durableStorage).recover(
                support.project.id(), staleMissing.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("upload failed");
        assertThat(support.record(staleMissing.file().id()).status()).isEqualTo(IdempotencyStatus.FAILED);

        var unknown = support.pending(false, true, null, null);
        var unknownStorage = new ProjectRecoveryTestSupport.StorageDecorator(support.durableStorage) {
            @Override public StoredFileInspection inspect(StorageKey key) {
                return StoredFileInspection.unknown(StorageFailureReason.ACCESS_DENIED);
            }
        };
        assertThatThrownBy(() -> support.recovery(unknownStorage).recover(
                support.project.id(), unknown.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("uncertain");
        assertPending(support, unknown.file().id());
    }

    @Test
    void provenHashOrSizeMismatchIsTerminalContentConflict() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var hash = support.pending(true, false, "0".repeat(64), null);
        assertThatThrownBy(() -> support.recovery(support.durableStorage).recover(
                support.project.id(), hash.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("integrity conflict");
        assertThat(support.record(hash.file().id()).status()).isEqualTo(IdempotencyStatus.FAILED);

        var size = support.pending(true, false, null, hash.file().sizeBytes() + 1);
        assertThatThrownBy(() -> support.recovery(support.durableStorage).recover(
                support.project.id(), size.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("integrity conflict");
        assertThat(support.record(size.file().id()).status()).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void existingCandidateDoesNotAuthorizeOrBlockCurrentPipelineRecovery() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var pending = support.pending(true, false, null, null);
        ContractCandidate existing = ContractCandidate.draft(UUID.randomUUID(), support.project.id(),
                pending.file().id(), "DIFFERENT", null, null, null, null, null,
                new BigDecimal("12.00"), null, null, null, null, java.util.List.of(), support.clock.instant());
        support.repositories.contractCandidateRepository().insertIfAbsent(existing);

        support.recovery(support.durableStorage).recover(
                support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META);

        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .containsExactly(existing);
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .isNotEmpty().allSatisfy(fact -> {
                    assertThat(fact.candidateId()).isNull();
                    assertThat(fact.parseResultId()).isNotNull();
                });
        assertThat(auditTypes(support)).containsExactly(
                "PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_COMPLETED");
    }

    @Test
    void deterministicExtractionIdentityConflictClosesDurableFailureWithoutDuplicateFacts() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var pending = support.pending(true, false, null, null);
        var proposed = support.ingestion.processPersisted(pending.file());
        var parseWinner = support.parseResults.insertIfAbsent(proposed.parseResults().get(0));
        ExtractionResult fact = proposed.extractions().get(0);
        ExtractionResult conflicting = new ExtractionResult(UUID.randomUUID(), fact.projectId(),
                parseWinner.fileId(), parseWinner.parseResultId(), null,
                new ExtractionKeyFactoryV2().create(parseWinner.fileId(),
                parseWinner.parseResultId(), fact.extractionVersion(), fact.factType(), fact.groupKey(),
                fact.sourceLocator()), fact.extractionVersion(),
                fact.factType(), fact.groupKey(), "conflicting", "conflicting", fact.valueAmount(),
                "conflicting evidence", fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator(),
                fact.createdAt());
        support.repositories.extractionResultRepository().insertIfAbsent(conflicting);
        ProjectFileIngestionService deterministicReplay = spy(support.ingestion);
        doReturn(proposed).when(deterministicReplay).processPersisted(pending.file());
        ProjectFileRecoveryService recovery = new ProjectFileRecoveryService(
                support.repositories.projectFileRepository(), support.durableStorage, support.policy,
                support.coordinator, deterministicReplay, () -> Optional.of(support.actor), support.clock);

        assertThatThrownBy(() -> recovery.recover(
                support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META))
                .isInstanceOf(BusinessException.class).hasMessageContaining("upload failed");

        assertFailed(support, pending.file().id(), "EXTRACTION_IDENTITY_CONFLICT");
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .isEmpty();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .containsExactly(conflicting);
        assertThat(auditTypes(support)).containsExactly(
                "PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_FAILED");
    }

    @Test
    void unknownCompleteOutcomeRemainsPendingAndInProgressForRecovery() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        ProjectFileIngestionTransactionCoordinator uncertain = spy(support.coordinator);
        doThrow(new DataAccessResourceFailureException("simulated completion outcome unknown"))
                .when(uncertain).completeUpload(any(), any(), any(), any());

        assertThatThrownBy(() -> support.ingestion(support.durableStorage, uncertain)
                .upload(support.command("unknown-complete")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("uncertain");

        var pending = support.repositories.projectFileRepository().findByProjectId(support.project.id())
                .stream().findFirst().orElseThrow();
        assertPending(support, pending.id());
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id())).isEmpty();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id())).isEmpty();
        assertThat(auditTypes(support)).containsExactly("PROJECT_FILE_UPLOAD_STARTED");
    }

    @Test
    void completeCommitResponseLossReplaysSucceededWithoutDuplicateFactsOrAudits() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        ProjectFileIngestionTransactionCoordinator responseLost = spy(support.coordinator);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("simulated response loss after commit");
        }).when(responseLost).completeUpload(any(), any(), any(), any());

        var recovered = support.ingestion(support.durableStorage, responseLost)
                .upload(support.command("complete-response-lost"));

        assertThat(recovered.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(support.record(recovered.id()).status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id())).isEmpty();
        int extractionCount = support.repositories.extractionResultRepository()
                .findByProjectId(support.project.id()).size();
        assertThat(extractionCount).isPositive();
        assertThat(auditTypes(support)).containsExactly(
                "PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_COMPLETED");

        support.recovery(support.durableStorage).recover(
                support.project.id(), recovered.id(), ProjectRecoveryTestSupport.META);
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .hasSize(extractionCount);
        assertThat(auditTypes(support)).containsExactly(
                "PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_COMPLETED");
    }

    private static void assertPending(ProjectRecoveryTestSupport support, java.util.UUID fileId) {
        assertThat(support.repositories.projectFileRepository().findByProjectIdAndId(support.project.id(), fileId))
                .get().extracting(file -> file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.UPLOAD_PENDING);
        assertThat(support.record(fileId).status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    private static void assertFailed(ProjectRecoveryTestSupport support, UUID fileId, String reasonCode) {
        assertThat(support.repositories.projectFileRepository().findByProjectIdAndId(support.project.id(), fileId))
                .get().satisfies(file -> {
                    assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.UPLOAD_FAILED);
                    assertThat(file.safeReasonCode()).isEqualTo(reasonCode);
                });
        assertThat(support.record(fileId).status()).isEqualTo(IdempotencyStatus.FAILED);
    }

    private static java.util.List<String> auditTypes(ProjectRecoveryTestSupport support) {
        return support.audits.stream().map(com.fuquan.audit.common.application.port.OperationAuditEvent::eventType)
                .toList();
    }
}
