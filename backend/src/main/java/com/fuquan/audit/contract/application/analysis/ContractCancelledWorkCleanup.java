package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.common.application.TaskCancellationRegistry;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;

/** Rediscover cleanup after restart; keep committed parse/evidence facts and durable cancellation receipts. */
@Service
public class ContractCancelledWorkCleanup {
    private final ContractAnalysisRepository cases;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileJobRepository jobs;
    private final FileStoragePort storage;
    private final TaskCancellationRegistry running;
    public ContractCancelledWorkCleanup(ContractAnalysisRepository cases,ProjectFileRepository files,ProjectFileParseResultRepository parses,
        ProjectFileJobRepository jobs,FileStoragePort storage,TaskCancellationRegistry running){
        this.cases=cases;this.files=files;this.parses=parses;this.jobs=jobs;this.storage=storage;this.running=running;
    }
    @Scheduled(fixedDelayString="${audit.material.cleanup-delay-ms:30000}")
    public void cleanup(){
        for(var id:cases.cancelledForCleanup(50)){
            var c=cases.find(id).orElse(null);
            if(c==null||!"USER_CANCELLED".equals(c.reasonCode())||running.isRunning(id))continue;
            if(jobs.byProject(c.projectId()).stream().anyMatch(j->running.isRunning(j.id())))continue;
            boolean complete=true;
            for(var f:files.findByProjectId(c.projectId())){
                if("USER_CANCELLED_CLEANED".equals(f.safeReasonCode())||parses.findLatestByFileId(f.id()).isPresent())continue;
                try{storage.delete(new StorageKey(f.storageKey()));files.update(f.fail("USER_CANCELLED_CLEANED",Instant.now()));}
                catch(RuntimeException failure){complete=false;org.slf4j.LoggerFactory.getLogger(getClass()).warn("CONTRACT_CANCEL_CLEANUP_RETRY exceptionType={}",failure.getClass().getSimpleName());}
            }
            if(complete)cases.cleanupComplete(id);
        }
    }
}
