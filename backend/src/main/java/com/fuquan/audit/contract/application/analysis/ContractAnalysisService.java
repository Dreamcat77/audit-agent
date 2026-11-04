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
    public ContractAnalysisCase owned(UUID id,CurrentUser actor){return repository.find(id).filter(c->c.owner().equals(actor.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}
    public ContractAnalysisCase forProject(UUID projectId,CurrentUser actor){return owned(identity(actor.userId(),projectId),actor);}
    public List<ContractAnalysisCase> recent(CurrentUser actor,int page,int size){
        return recent(actor,page,size,null,null,null);
    }
    public List<ContractAnalysisCase> recent(CurrentUser actor,int page,int size,
                                             String projectName, Instant from, Instant to){
        if(page<1||page>100000||size<1||size>50)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        return repository.recent(actor.userId(),Math.multiplyExact(page-1,size),size,
                projectName == null ? null : projectName.trim(),from,to);
    }
}
