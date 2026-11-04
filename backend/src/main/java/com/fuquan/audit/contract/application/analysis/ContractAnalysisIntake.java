package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.ProjectCommandTransactionCoordinator;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Project admission and case creation commit together before the first byte upload. */
@Service
public class ContractAnalysisIntake {
    public record Receipt(UUID projectId,UUID caseId) {}
    private final ProjectCommandTransactionCoordinator projects;private final ContractAnalysisRepository repository;
    private final OperationAuditService audit;private final Clock clock;
    public ContractAnalysisIntake(ProjectCommandTransactionCoordinator projects,ContractAnalysisRepository repository,OperationAuditService audit,Clock clock){this.projects=projects;this.repository=repository;this.audit=audit;this.clock=clock;}
    @Transactional
    public Receipt start(String key,int fileCount,CurrentUser actor,String requestId,String traceId,String sourceIp){
        if(fileCount<1||fileCount>200||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        var project=projects.create(key,requestId,traceId,sourceIp);if(!project.createdBy().equals(actor.userId()))throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        UUID id=identity(actor.userId(),project.id());var old=repository.find(id);
        if(old.isPresent()){
            if(old.get().expectedUploads()!=fileCount)throw new BusinessException(BusinessErrorCode.CONFLICT,"同一次上传的文件数量不能更改，请继续原操作");
            return new Receipt(project.id(),id);
        }
        // New batches reserve bounded time for serial scanning before model work.
        // Replays retain the original persisted deadline; uploads have a separate shorter window.
        var now=clock.instant();var c=new ContractAnalysisCase(id,project.id(),actor.userId(),sha256("intake-v1/"+actor.userId()+"/"+project.id()+"/"+fileCount),State.QUEUED,0,null,null,0,now,now.plusSeconds(21600),List.of(),List.of(),"WAITING_FOR_UPLOAD",fileCount);
        repository.insert(c);
        audit.success("CONTRACT_ANALYSIS_CREATED",actor,"CONTRACT_ANALYSIS_CASE",id.toString(),null,null,Map.of("expectedUploads",fileCount),"接收合同分析批次，后台自动处理");
        return new Receipt(project.id(),id);
    }
}
