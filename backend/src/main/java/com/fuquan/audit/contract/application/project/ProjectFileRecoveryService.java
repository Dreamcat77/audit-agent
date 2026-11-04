package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.application.project.ProjectFileIngestionTransactionCoordinator.UploadStart;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectFileRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository jobs;
    private final ProjectFileRepository files;
    private final FileStoragePort storage;
    private final ProjectFilePolicy policy;
    private final ProjectFileIngestionTransactionCoordinator transactions;
    private final ProjectFileIngestionService ingestion;
    private final AuthContext authContext;
    private final Clock clock;

    public ProjectFileRecoveryService(ProjectFileRepository files, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectFileIngestionService ingestion, AuthContext authContext, Clock clock) {
        this.files=files; this.storage=storage; this.policy=policy; this.transactions=transactions;
        this.ingestion=ingestion; this.authContext=authContext; this.clock=clock;
    }

    public ProjectFile recover(UUID projectId, UUID fileId, ProjectRequestMetadata metadata) {
        CurrentUser actor = authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        ProjectFile file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        IdempotencyRecord original = transactions.findOriginal(fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "durable upload identity is missing"));
        validateOriginal(original, projectId, fileId, actor);
        if (original.status() == IdempotencyStatus.SUCCEEDED) {
            if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "completed upload state is inconsistent");
            }
            return file;
        }
        if (original.status() == IdempotencyStatus.FAILED) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotent upload previously failed");
        }
        if (file.ingestionStatus() != ProjectFileIngestionStatus.UPLOAD_PENDING) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "pending upload state is inconsistent");
        }
        StoredFileInspection inspection = storage.inspect(new StorageKey(file.storageKey()));
        if (inspection.status() == StoredFileInspection.Status.UNKNOWN) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file storage outcome is uncertain");
        }
        UploadStart start = transactions.resume(original, file);
        if (inspection.status() == StoredFileInspection.Status.DEFINITELY_MISSING) {
            if (!file.isPendingStale(clock.instant(), policy.pendingRecoveryAge())) {
                throw new BusinessException(BusinessErrorCode.CONFLICT, "project file upload is in progress");
            }
            transactions.failUpload(start, actor, "STORAGE_DEFINITELY_MISSING", metadata);
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file upload failed");
        }
        if (inspection.file().size() != file.sizeBytes() || !inspection.file().sha256().equals(file.sha256())) {
            transactions.failUpload(start, actor, StorageFailureReason.CONTENT_CONFLICT.name(), metadata);
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file storage integrity conflict");
        }
        if(ProjectFileIngestionTransactionCoordinator.ASYNC_SCOPE.equals(original.scope())) {
            if(jobs==null)throw new BusinessException(BusinessErrorCode.CONFLICT,"processing repository unavailable");
            return transactions.acceptForProcessing(start,jobs,actor,metadata);
        }
        boolean completionAttempted = false;
        try {
            ProjectFileIngestionTransactionCoordinator.ProcessingOutcome outcome = ingestion.processPersisted(file);
            completionAttempted = true;
            return transactions.completeUpload(start, outcome, actor, metadata);
        } catch (FileStorageException storageFailure) {
            if (completionAttempted) {
                return ingestion.reconcileCompleteFailure(start, actor, metadata, storageFailure,
                        "project file recovery outcome is uncertain");
            }
            if (isTerminal(storageFailure)) {
                transactions.failUpload(start, actor, storageFailure.reason().name(), metadata);
                throw new BusinessException(BusinessErrorCode.CONFLICT, "project file recovery failed");
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file recovery outcome is uncertain");
        } catch (RuntimeException ordinaryFailure) {
            if (completionAttempted) {
                return ingestion.reconcileCompleteFailure(start, actor, metadata, ordinaryFailure,
                        "project file recovery outcome is uncertain");
            }
            transactions.failUpload(start, actor, safeReason(ordinaryFailure), metadata);
            throw ordinaryFailure;
        }
    }

    private static void validateOriginal(IdempotencyRecord record, UUID projectId, UUID fileId, CurrentUser actor) {
        if (!(ProjectFileIngestionTransactionCoordinator.SCOPE.equals(record.scope())
                ||ProjectFileIngestionTransactionCoordinator.ASYNC_SCOPE.equals(record.scope()))
                || !"PROJECT".equals(record.resourceType()) || !projectId.toString().equals(record.resourceId())
                || !ProjectFileIngestionTransactionCoordinator.RESOURCE.equals(record.resultResourceType())
                || !fileId.toString().equals(record.resultResourceId()) || !actor.userId().equals(record.actorId())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "durable upload identity mismatch");
        }
    }

    private static String safeReason(RuntimeException failure) {
        String value = failure.getClass().getSimpleName();
        return value.substring(0, Math.min(value.length(), 64));
    }

    private static boolean isTerminal(FileStorageException failure) {
        return failure.reason() == StorageFailureReason.PUBLICATION_UNSUPPORTED
                || failure.reason() == StorageFailureReason.CONTENT_CONFLICT;
    }
}
