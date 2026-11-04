package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProjectFileIngestionTransactionCoordinator {
    public static final String SCOPE = "PROJECT_FILE_UPLOAD";
    public static final String ASYNC_SCOPE = "PROJECT_FILE_UPLOAD_ASYNC";
    public static final String RESOURCE = "PROJECT_FILE";
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ContractCandidateRepository candidates;
    private final ExtractionResultRepository extractions;
    private final ProjectFileParseResultRepository parseResults;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;
    private final FlowDebugLogger flowDebug;
    private final ExtractionKeyFactory extractionKeys = new ExtractionKeyFactory();
    private final ExtractionKeyFactoryV2 extractionKeysV2 = new ExtractionKeyFactoryV2();

    public ProjectFileIngestionTransactionCoordinator(ProjectRepository projects, ProjectFileRepository files,
            ContractCandidateRepository candidates, ExtractionResultRepository extractions,
            ProjectFileParseResultRepository parseResults, IdempotencyPort idempotency,
            OperationAuditPort audit, Clock clock, FlowDebugLogger flowDebug) {
        this.projects = projects; this.files = files; this.candidates = candidates; this.extractions = extractions;
        this.parseResults = parseResults;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.flowDebug = flowDebug;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UploadStart startUpload(ProjectFile pending, CurrentUser actor, String operationKey,
                                   String fingerprint, ProjectRequestMetadata metadata) {
        return start(SCOPE,pending,actor,operationKey,fingerprint,metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UploadStart startAsyncUpload(ProjectFile pending, CurrentUser actor, String operationKey,
                                   String fingerprint, ProjectRequestMetadata metadata) {
        return start(ASYNC_SCOPE,pending,actor,operationKey,fingerprint,metadata);
    }

    private UploadStart start(String scope,ProjectFile pending, CurrentUser actor, String operationKey,
                                   String fingerprint, ProjectRequestMetadata metadata) {
        flowDebug.log("PROJECT_UPLOAD_START_TX_ENTER", "PROJECT", "APPLICATION", "startUpload",
                Map.of("projectFileId", pending.id(), "sizeBytes", pending.sizeBytes()));
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(scope, operationKey, actor.userId(),
                "PROJECT", pending.projectId().toString(), fingerprint, RESOURCE, pending.id().toString(),
                metadata.requestId(), metadata.traceId(), clock.instant()));
        if (!claim.acquired()) return replayStart(claim.record(), pending.projectId(), actor, fingerprint);
        try {
            projects.findById(pending.projectId()).orElseThrow(() ->
                    new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
            files.insert(pending);
            audit.append(event("PROJECT_FILE_UPLOAD_STARTED", "uploadStart", actor, pending,
                    "UPLOAD_PENDING", metadata));
            flowDebug.log("PROJECT_UPLOAD_START_TX_COMMIT", "PROJECT", "APPLICATION", "startUpload",
                    Map.of("projectFileId", pending.id(), "status", "UPLOAD_PENDING"));
            return new UploadStart(claim.record(), pending, false, false);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProjectFile completeUpload(UploadStart start, ProcessingOutcome outcome,
                                      CurrentUser actor, ProjectRequestMetadata metadata) {
        flowDebug.log("PROJECT_UPLOAD_COMPLETE_TX_ENTER", "PROJECT", "APPLICATION", "completeUpload",
                Map.of("projectFileId", start.file().id()));
        IdempotencyRecord current = current(start.idempotency());
        if (current.status() == IdempotencyStatus.SUCCEEDED) {
            return files.findByProjectIdAndId(start.file().projectId(), start.file().id())
                    .orElseThrow(() -> new IllegalStateException("completed project file is missing"));
        }
        if (current.status() != IdempotencyStatus.IN_PROGRESS) conflict("upload is not completable");
        ProjectFile persisted = files.findByProjectIdAndId(start.file().projectId(), start.file().id())
                .orElseThrow(() -> new IllegalStateException("pending project file is missing"));
        requireStorageIdentity(persisted, start.file());
        persistOutcome(outcome);
        audit.append(event("PROJECT_FILE_UPLOAD_COMPLETED", "uploadComplete", actor, outcome.root(),
                "AVAILABLE", metadata));
        idempotency.markSucceeded(current, RESOURCE, outcome.root().id().toString(), clock.instant());
        flowDebug.log("PROJECT_UPLOAD_COMPLETE_TX_COMMIT", "PROJECT", "APPLICATION", "completeUpload",
                Map.of("projectFileId", outcome.root().id(), "childCount", outcome.children().size(),
                        "candidateCount", outcome.candidates().size(), "extractionCount", outcome.extractions().size(),
                        "parseResultCount", outcome.parseResults().size()));
        return outcome.root();
    }

    private void persistOutcome(ProcessingOutcome outcome) {
        Map<UUID, UUID> childIds = new HashMap<>();
        for (ProjectFile child : outcome.children()) {
            Optional<ProjectFile> existing = files.findByProjectIdAndParentArchiveFileIdAndRelativePath(
                    child.projectId(), child.parentArchiveFileId(), child.normalizedRelativePath());
            if (existing.isEmpty()) {
                files.insert(child);
                childIds.put(child.id(), child.id());
            } else {
                requireChildStorageIdentity(existing.get(), child);
                childIds.put(child.id(), existing.get().id());
            }
        }
        Map<UUID, ProjectFileParseResult> storedParseResultsByProposedSourceFileId = new HashMap<>();
        for (ProjectFileParseResult result : outcome.parseResults()) {
            UUID sourceId = childIds.getOrDefault(result.fileId(), result.fileId());
            ProjectFileParseResult normalized = sourceId.equals(result.fileId()) ? result
                    : new ProjectFileParseResult(result.parseResultId(), result.projectId(), sourceId,
                    result.fileSha256(), result.parseVersion(), result.parseOrigin(), result.parseStatus(),
                    result.parseMethod(), result.engine(), result.engineVersion(), result.canonicalText(),
                    result.canonicalTextSha256(), result.pageCount(), result.durationMs(), result.confidence(),
                    result.supersedesParseResultId(), result.createdAt(), result.completedAt(), result.versionNo());
            ProjectFileParseResult stored = parseResults.insertIfAbsent(normalized);
            storedParseResultsByProposedSourceFileId.put(result.fileId(), stored);
        }
        Map<UUID, UUID> candidateIds = new HashMap<>();
        for (ContractCandidate proposed : outcome.candidates()) {
            UUID sourceId = childIds.getOrDefault(proposed.sourceContractFileId(), proposed.sourceContractFileId());
            ContractCandidate candidate = sourceId.equals(proposed.sourceContractFileId()) ? proposed
                    : new ContractCandidate(proposed.id(), proposed.projectId(), sourceId,
                    proposed.sourceParseResultId(), proposed.sourceRoleDecisionId(),
                    proposed.sourceAuthoritySource(), proposed.sourceFactFingerprint(), proposed.contractNo(),
                    proposed.contractName(), proposed.contractType(), proposed.partyA(), proposed.partyB(),
                    proposed.partyBCreditCode(), proposed.contractAmount(), proposed.amountInWords(),
                    proposed.awardAmount(), proposed.awardSupplier(), proposed.awardSupplierCreditCode(),
                    proposed.lineItems(), proposed.promotionState(), proposed.versionNo(),
                    proposed.promotedContractId(), proposed.promotedBy(), proposed.promotedAt(),
                    proposed.createdAt(), proposed.updatedAt());
            ContractCandidate stored = candidates.insertIfAbsent(candidate);
            candidateIds.put(proposed.id(), stored.id());
        }
        for (ExtractionResult extraction : outcome.extractions()) {
            UUID candidateId = extraction.candidateId() == null ? null
                    : candidateIds.getOrDefault(extraction.candidateId(), extraction.candidateId());
            UUID sourceId = childIds.getOrDefault(extraction.sourceFileId(), extraction.sourceFileId());
            ProjectFileParseResult storedParseResult = storedParseResultsByProposedSourceFileId
                    .get(extraction.sourceFileId());
            UUID parseResultId = storedParseResult == null
                    ? extraction.parseResultId() : storedParseResult.parseResultId();
            String extractionKey = storedParseResult != null
                    ? extractionKeysV2.create(storedParseResult.fileId(), storedParseResult.parseResultId(),
                    extraction.extractionVersion(), extraction.factType(), extraction.groupKey(), extraction.sourceLocator())
                    : extractionKeys.create(sourceId, extraction.extractionVersion(), extraction.factType(),
                    extraction.groupKey(), extraction.sourceLocator());
            ExtractionResult normalized = Objects.equals(parseResultId, extraction.parseResultId())
                    && Objects.equals(candidateId, extraction.candidateId())
                    && sourceId.equals(extraction.sourceFileId())
                    && extractionKey.equals(extraction.extractionKey()) ? extraction
                    : new ExtractionResult(extraction.id(), extraction.projectId(), sourceId,
                    parseResultId, candidateId, extractionKey, extraction.extractionVersion(), extraction.factType(),
                    extraction.groupKey(), extraction.valueText(), extraction.normalizedValue(),
                    extraction.valueAmount(), extraction.sourceText(), extraction.pageNumber(),
                    extraction.paragraphIndex(), extraction.sourceLocator(), extraction.createdAt());
            extractions.insertIfAbsent(normalized);
        }
        files.update(outcome.root());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProjectFile acceptForProcessing(UploadStart start,ProjectFileJobRepository jobs,
            CurrentUser actor,ProjectRequestMetadata metadata) {
        ProjectFile accepted=completeUpload(start,new ProcessingOutcome(start.file().complete(
                ProjectFileParseStatus.PENDING,null,null,clock.instant()),List.of(),List.of(),List.of(),List.of()),actor,metadata);
        if(jobs.byFile(accepted.projectId(),accepted.id()).isEmpty()) {
            jobs.insert(ProjectFileJob.queued(accepted.projectId(),accepted.id(),accepted.sha256(),actor.userId(),
                    metadata.requestId(),metadata.traceId(),metadata.sourceIp(),clock.instant()));
        }
        return accepted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeProcessing(ProjectFileJob job,String token,ProcessingOutcome outcome,ProjectFileJobRepository jobs,
            CurrentUser actor,ProjectRequestMetadata metadata) {
        completeProcessing(job,token,outcome,jobs,actor,metadata,false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeProcessing(ProjectFileJob job,String token,ProcessingOutcome outcome,ProjectFileJobRepository jobs,
            CurrentUser actor,ProjectRequestMetadata metadata,boolean automaticProgression) {
        ProjectFile persisted=files.findByProjectIdAndId(job.projectId(),job.fileId()).orElseThrow();
        requireStorageIdentity(persisted,outcome.root());
        if(!persisted.sha256().equals(job.fileSha256())) conflict("processing input identity changed");
        jobs.requireLease(job.id(),token,clock.instant());
        persistOutcome(outcome);
        String status=outcome.root().parseStatus()==ProjectFileParseStatus.FAILED ? "FAILED" : "SUCCEEDED";
        UUID output=parseResults.findLatestByFileId(job.fileId()).map(ProjectFileParseResult::parseResultId).orElse(null);
        boolean advance = automaticProgression && output != null && "SUCCEEDED".equals(status);
        if(!jobs.finish(job.id(),token,advance ? "QUEUED" : status,
                advance ? "AUTO_PROGRESSION_PENDING" : outcome.root().safeReasonCode(),output,clock.instant())) {
            conflict("processing lease no longer owns completion");
        }
        for(ProjectFile proposed:outcome.children()) {
            ProjectFile child=files.findByProjectIdAndParentArchiveFileIdAndRelativePath(
                    proposed.projectId(),proposed.parentArchiveFileId(),proposed.normalizedRelativePath()).orElseThrow();
            if(jobs.byFile(child.projectId(),child.id()).isEmpty()) {
                jobs.insert(ProjectFileJob.queued(child.projectId(),child.id(),child.sha256(),actor.userId(),
                        metadata.requestId(),metadata.traceId(),metadata.sourceIp(),clock.instant()));
            }
        }
        audit.append(event("PROJECT_FILE_PROCESSING_COMPLETED","processFile",actor,outcome.root(),status,metadata));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean closeProcessing(ProjectFileJob job,String token,String status,String reason,ProjectFileJobRepository jobs,
            CurrentUser actor,ProjectRequestMetadata metadata) {
        boolean changed=jobs.finish(job.id(),token,status,bound(reason),job.parseResultId(),clock.instant());
        if(changed) {
            ProjectFile file=files.findByProjectIdAndId(job.projectId(),job.fileId()).orElseThrow();
            audit.append(event("PROJECT_FILE_PROCESSING_STATE","processFile",actor,file,status,metadata));
        }
        return changed;
    }

    public Optional<IdempotencyRecord> findAsyncOperation(String key) {
        return idempotency.findByScopeAndKey(ASYNC_SCOPE,key);
    }

    /** Records the original owner, not an invented authorized worker principal. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blockUnavailableOwner(ProjectFileJob job,String token,ProjectFileJobRepository jobs) {
        if(jobs.finish(job.id(),token,"UNKNOWN","FILE_OWNER_UNAVAILABLE",null,clock.instant())) {
            audit.append(new OperationAuditEvent("PROJECT_FILE_PROCESSING_BLOCKED","CONTRACT","processFile",
                    job.actorId(),null,"PROJECT_FILE",job.fileId().toString(),null,
                    OperationAuditResultStatus.FAILURE,"UNKNOWN","Background processing blocked",
                    "FILE_OWNER_UNAVAILABLE",job.requestId(),job.traceId(),job.sourceIp(),clock.instant()));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProjectFileJob retryProcessing(UUID projectId,UUID fileId,ProjectFileJobRepository jobs,
            CurrentUser actor,ProjectRequestMetadata metadata) {
        ProjectFile file=files.findByProjectIdAndIdForUpdate(projectId,fileId).orElseThrow(
                ()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        ProjectFileJob job=jobs.byFile(projectId,fileId).orElseThrow(
                ()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        if(parseResults.findLatestByFileId(fileId).isPresent() || !jobs.retry(projectId,job.id(),clock.instant())) {
            conflict("only a proven failed processing job without a published parse can be retried");
        }
        files.update(file.complete(ProjectFileParseStatus.PENDING,null,null,clock.instant()));
        audit.append(event("PROJECT_FILE_PROCESSING_RETRIED","retryFile",actor,file,"QUEUED",metadata));
        return jobs.find(job.id()).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failUpload(UploadStart start, CurrentUser actor, String safeReason,
                           ProjectRequestMetadata metadata) {
        flowDebug.log("PROJECT_UPLOAD_FAILURE_TX_ENTER", "PROJECT", "APPLICATION", "failUpload",
                Map.of("projectFileId", start.file().id(), "reasonCode", bound(safeReason)));
        IdempotencyRecord current = current(start.idempotency());
        if (current.status() != IdempotencyStatus.IN_PROGRESS) return;
        ProjectFile persisted = files.findByProjectIdAndId(start.file().projectId(), start.file().id())
                .orElseThrow(() -> new IllegalStateException("pending project file is missing"));
        ProjectFile failed = persisted.fail(bound(safeReason), clock.instant());
        files.update(failed);
        audit.append(event("PROJECT_FILE_UPLOAD_FAILED", "uploadFailure", actor, failed,
                bound(safeReason), metadata));
        idempotency.markFailed(current, clock.instant());
        flowDebug.log("PROJECT_UPLOAD_FAILURE_TX_COMMIT", "PROJECT", "APPLICATION", "failUpload",
                Map.of("projectFileId", failed.id(), "reasonCode", bound(safeReason)));
    }

    public Optional<IdempotencyRecord> findOriginal(UUID fileId) {
        return idempotency.findByResultResource(RESOURCE, fileId.toString());
    }

    @Transactional(readOnly = true)
    public IdempotencyRecord readCurrent(UploadStart start) {
        return current(start.idempotency());
    }

    @Transactional(readOnly = true)
    public ProjectFile replayCompleted(UploadStart start) {
        IdempotencyRecord durable = current(start.idempotency());
        if (durable.status() != IdempotencyStatus.SUCCEEDED) {
            conflict("upload is not completed");
        }
        ProjectFile file = files.findByProjectIdAndId(start.file().projectId(), start.file().id())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "completed project file is missing"));
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE) {
            conflict("completed upload state is inconsistent");
        }
        return file;
    }

    public UploadStart resume(IdempotencyRecord record, ProjectFile file) {
        return new UploadStart(record, file, false, true);
    }

    private UploadStart replayStart(IdempotencyRecord record, UUID projectId, CurrentUser actor, String fingerprint) {
        if (!record.actorId().equals(actor.userId()) || !record.resourceId().equals(projectId.toString())
                || !record.requestFingerprint().equals(fingerprint)) conflict("idempotency key fingerprint conflict");
        ProjectFile file = files.findByProjectIdAndId(projectId, UUID.fromString(record.resultResourceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "idempotent upload state is inconsistent"));
        if (record.status() == IdempotencyStatus.SUCCEEDED) return new UploadStart(record, file, true, false);
        if (record.status() == IdempotencyStatus.FAILED) conflict("idempotent upload previously failed");
        return new UploadStart(record, file, false, true);
    }

    private IdempotencyRecord current(IdempotencyRecord expected) {
        return idempotency.findByScopeAndKey(expected.scope(), expected.idempotencyKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "durable upload identity is missing"));
    }

    private static void requireStorageIdentity(ProjectFile expected, ProjectFile actual) {
        if (!expected.projectId().equals(actual.projectId()) || !expected.id().equals(actual.id())
                || !expected.storageKey().equals(actual.storageKey()) || !expected.sha256().equals(actual.sha256())
                || expected.sizeBytes() != actual.sizeBytes()) conflict("project file storage identity conflict");
    }

    private static void requireChildStorageIdentity(ProjectFile expected, ProjectFile actual) {
        if (!expected.projectId().equals(actual.projectId())
                || !Objects.equals(expected.parentArchiveFileId(), actual.parentArchiveFileId())
                || !Objects.equals(expected.normalizedRelativePath(), actual.normalizedRelativePath())
                || !expected.storageKey().equals(actual.storageKey()) || !expected.sha256().equals(actual.sha256())
                || expected.sizeBytes() != actual.sizeBytes()) conflict("archive child storage identity conflict");
    }

    private OperationAuditEvent event(String type, String action, CurrentUser actor, ProjectFile file,
                                      String result, ProjectRequestMetadata metadata) {
        OperationAuditResultStatus status = "PROJECT_FILE_UPLOAD_FAILED".equals(type)
                ? OperationAuditResultStatus.FAILURE : OperationAuditResultStatus.SUCCESS;
        return new OperationAuditEvent(type, "CONTRACT", action, actor.userId(), actor.username(), RESOURCE,
                file.id().toString(), null, status, result, action,
                "{\"status\":\"" + bound(result) + "\",\"category\":\"" + file.category().name() + "\"}",
                metadata.requestId(), metadata.traceId(), metadata.sourceIp(), clock.instant());
    }

    private static String bound(String value) { return value == null ? "UNKNOWN" : value.substring(0, Math.min(64, value.length())); }
    private static void conflict(String message) { throw new BusinessException(BusinessErrorCode.CONFLICT, message); }

    public record UploadStart(IdempotencyRecord idempotency, ProjectFile file, boolean replay, boolean inProgress) { }
    public record ProcessingOutcome(ProjectFile root, List<ProjectFile> children,
                                    List<ContractCandidate> candidates, List<ExtractionResult> extractions,
                                    List<ProjectFileParseResult> parseResults) {
        public ProcessingOutcome {
            children=List.copyOf(children); candidates=List.copyOf(candidates);
            extractions=List.copyOf(extractions); parseResults=List.copyOf(parseResults);
        }
    }
}
