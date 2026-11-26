package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

@Service
public class ContractAnalysisService {
    private com.fuquan.audit.common.application.TaskCancellationRegistry cancellationRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCancellationRegistry(com.fuquan.audit.common.application.TaskCancellationRegistry value){this.cancellationRegistry=value;}
    private final ContractAnalysisRepository repository;private final ContractAnalysisTransactions tx;
    private final ContractAnalysisSources sources;private final Clock clock;
    public ContractAnalysisService(ContractAnalysisRepository repository,ContractAnalysisTransactions tx,ContractAnalysisSources sources,Clock clock){this.repository=repository;this.tx=tx;this.sources=sources;this.clock=clock;}
    public ContractAnalysisCase admit(UUID projectId,CurrentUser actor){
        var manifest=sources.manifest(projectId,actor);
        String digest=sha256(manifest.stream().map(s->s.fileId()+":"+s.sha256()+":"+s.size()).collect(java.util.stream.Collectors.joining("\n")));
        UUID id=identity(actor.userId(),projectId);var current=repository.find(id);
        if(current.isPresent())return replay(current.get(),digest,actor);
        var now=clock.instant();var value=new ContractAnalysisCase(id,projectId,actor.userId(),digest,State.QUEUED,0,null,null,0,now,now.plusSeconds(Math.min(7200,1800+180L*manifest.size())),manifest,List.of(),null);
        try{tx.create(value,actor);return value;}catch(org.springframework.dao.DuplicateKeyException concurrent){return replay(repository.find(id).orElseThrow(()->concurrent),digest,actor);}
    }
    private ContractAnalysisCase replay(ContractAnalysisCase value,String digest,CurrentUser actor){
        if(!value.owner().equals(actor.userId()))throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        if(!value.inputDigest().equals(digest))throw new BusinessException(BusinessErrorCode.CONFLICT,"本次分析材料已冻结；新增材料请作为新一批上传，原结果仍可查看");
        return value;
    }
    public ContractAnalysisCase owned(UUID id,CurrentUser actor){return repository.find(id).filter(c->actor.roles().contains("SYSTEM_ADMIN")||c.owner().equals(actor.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}
    public ContractAnalysisCase cancel(UUID id,CurrentUser actor){
        var current=owned(id,actor);
        if("USER_CANCELLED".equals(current.reasonCode()))return current;
        if(current.terminal())throw new BusinessException(BusinessErrorCode.CONFLICT,"当前任务已结束，无法取消");
        var cancelled=current.release(State.FAILED,current.sources(),List.of(),"USER_CANCELLED");
        if(!tx.save(current,cancelled,actor))throw new BusinessException(BusinessErrorCode.CONFLICT,"任务状态已变化，请刷新后重试");
        if(cancellationRegistry!=null){cancellationRegistry.interrupt(id);sources.processingJobs(current.projectId()).forEach(cancellationRegistry::interrupt);}
        return repository.find(id).orElse(cancelled);
    }
    public ContractAnalysisCase forProject(UUID projectId,CurrentUser actor){return owned(sources.analysisIdentity(projectId,actor),actor);}
    public List<ContractAnalysisCase> recent(CurrentUser actor,int page,int size){
        return recent(actor,page,size,null,null,null);
    }
    public List<ContractAnalysisCase> recent(CurrentUser actor,int page,int size,
                                             String projectName, Instant from, Instant to){
        if(page<1||page>100000||size<1||size>50)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        return repository.recent(actor.roles().contains("SYSTEM_ADMIN")?null:actor.userId(),Math.multiplyExact(page-1,size),size,
                projectName == null ? null : projectName.trim(),from,to);
    }
}
