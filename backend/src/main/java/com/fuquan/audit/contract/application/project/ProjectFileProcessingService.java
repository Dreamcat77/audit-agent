package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.review.application.port.OcrCapacityUnavailableException;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Short DB claims/completion surround deterministic parse/OCR outside transactions. */
@Service
public class ProjectFileProcessingService {
    private final ProjectFileIngestionService ingestion;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final FileStoragePort storage;
    private final ProjectFileIngestionTransactionCoordinator transactions;
    private final ProjectFileJobRepository jobs;
    private final Clock clock;
    public ProjectFileProcessingService(ProjectFileIngestionService ingestion,ProjectFileRepository files,
            ProjectFileParseResultRepository parses,FileStoragePort storage,ProjectFileIngestionTransactionCoordinator transactions,
            ProjectFileJobRepository jobs,Clock clock) {
        this.ingestion=ingestion;this.files=files;this.parses=parses;this.storage=storage;
        this.transactions=transactions;this.jobs=jobs;this.clock=clock;
    }
    public ProjectFileJob process(UUID id,CurrentUser actor,Consumer<String> onClaim) {
        ProjectFileJob original=jobs.find(id).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        if(!original.actorId().equals(actor.userId()))throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        String token=UUID.randomUUID().toString();
        if(!jobs.claim(id,token,clock.instant(),clock.instant().plusSeconds(900)))return original;
        ProjectFileJob job=jobs.find(id).orElseThrow();
        ProjectRequestMetadata metadata=new ProjectRequestMetadata(job.requestId(),job.traceId(),job.sourceIp());
        boolean completionAttempted=false;
        try {
            onClaim.accept(token);
            ProjectFile file=files.findByProjectIdAndId(job.projectId(),job.fileId()).orElseThrow();
            if(!file.sha256().equals(job.fileSha256())||file.ingestionStatus()!=ProjectFileIngestionStatus.AVAILABLE) {
                return close(job,token,"UNKNOWN","INPUT_IDENTITY_UNPROVEN",actor,metadata);
            }
            StoredFileInspection inspected=storage.inspect(new StorageKey(file.storageKey()));
            if(inspected.status()==StoredFileInspection.Status.UNKNOWN) {
                return close(job,token,"UNKNOWN","STORAGE_UNKNOWN",actor,metadata);
            }
            if(inspected.status()==StoredFileInspection.Status.DEFINITELY_MISSING) {
                return close(job,token,"FAILED","STORAGE_DEFINITELY_MISSING",actor,metadata);
            }
            if(inspected.file().size()!=file.sizeBytes()||!inspected.file().sha256().equals(file.sha256())) {
                return close(job,token,"UNKNOWN","STORAGE_IDENTITY_CONFLICT",actor,metadata);
            }
            var published = parses.findLatestByFileId(file.id());
            if(published.isPresent() && ingestion.automaticProgressionEnabled()
                    && published.get().parseResultId().equals(job.parseResultId())) {
                // Parsing and automatic advancement share one durable job. A restart after
                // parse publication resumes decisions/facts, never reparses or resends blindly.
                ingestion.advance(job.projectId(),metadata,actor);
                return close(job,token,"SUCCEEDED",null,actor,metadata);
            }
            if(published.isPresent()) {
                return close(job,token,"UNKNOWN","PARSE_PUBLICATION_REQUIRES_RECONCILIATION",actor,metadata);
            }
            var outcome=ingestion.processQueued(file);
            completionAttempted=true;
            boolean automatic = ingestion.automaticProgressionEnabled();
            if(automatic) transactions.completeProcessing(job,token,outcome,jobs,actor,metadata,true);
            else transactions.completeProcessing(job,token,outcome,jobs,actor,metadata);
            try { if(!automatic) ingestion.advance(job.projectId(),metadata,actor); }
            catch(RuntimeException failure) {
                try(var span=PipelineDiagnostics.start("CONTRACT","ROLE_PROGRESSION_PENDING",job.fileId(),Map.of())){span.fail(failure);}
            }
            return jobs.find(id).orElseThrow();
        } catch(OcrCapacityUnavailableException busy) {
            return close(job,token,"QUEUED","OCR_CAPACITY_BUSY",actor,metadata);
        } catch(com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException uncertain) {
            return close(job,token,"UNKNOWN","OCR_OUTCOME_UNPROVEN",actor,metadata);
        } catch(RuntimeException failure) {
            if(completionAttempted) {
                // Never overwrite uncertain transaction outcome with a failed fact.
                ProjectFileJob current=jobs.find(id).orElseThrow();
                if(Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(current.status()))return current;
                throw failure;
            }
            boolean known=failure instanceof com.fuquan.audit.contract.application.project.archive.ProjectArchiveSecurityException;
            return close(job,token,known?"FAILED":"UNKNOWN",known?
                    ((com.fuquan.audit.contract.application.project.archive.ProjectArchiveSecurityException)failure).reasonCode():
                    "PROCESSING_OUTCOME_UNPROVEN",actor,metadata);
        }
    }
    public void blockUnavailableOwner(UUID id) {
        String token=UUID.randomUUID().toString();
        if(jobs.claim(id,token,clock.instant(),clock.instant().plusSeconds(900))) {
            transactions.blockUnavailableOwner(jobs.find(id).orElseThrow(),token,jobs);
        }
    }
    public ProjectFileJob retry(UUID projectId,UUID fileId,CurrentUser actor,ProjectRequestMetadata metadata) {
        if(Collections.disjoint(actor.roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","LEGAL","PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"))) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        }
        return transactions.retryProcessing(projectId,fileId,jobs,actor,metadata);
    }
    private ProjectFileJob close(ProjectFileJob job,String token,String status,String reason,CurrentUser actor,ProjectRequestMetadata metadata) {
        transactions.closeProcessing(job,token,status,reason,jobs,actor,metadata);
        return jobs.find(job.id()).orElseThrow();
    }
}
