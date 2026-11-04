package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContractAnalysisTransactions {
    private final ContractAnalysisRepository repository;
    private final OperationAuditService audit;
    private final Clock clock;
    public ContractAnalysisTransactions(ContractAnalysisRepository repository,OperationAuditService audit,Clock clock){this.repository=repository;this.audit=audit;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void create(ContractAnalysisCase value,CurrentUser actor){
        repository.insert(value);
        audit.success("CONTRACT_ANALYSIS_CREATED",actor,"CONTRACT_ANALYSIS_CASE",value.id().toString(),null,null,
                Map.of("files",value.sources().size(),"status",value.status()),"创建独立合同分析案件");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean save(ContractAnalysisCase previous,ContractAnalysisCase next,CurrentUser actor){
        if(!actor.userId().equals(previous.owner()))throw new IllegalArgumentException("CASE_OWNER_MISMATCH");
        if(!repository.replace(previous,next,clock.instant()))return false;
        audit.success("CONTRACT_ANALYSIS_CHECKPOINT",actor,"CONTRACT_ANALYSIS_CASE",next.id().toString(),null,null,
                Map.of("status",next.status(),"version",next.version()),"保存合同自动分析进度");
        return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void blockOwner(ContractAnalysisCase value){
        if(!value.claimable(clock.instant()))return;
        var claimed=value.claim(java.util.UUID.randomUUID().toString(),clock.instant());
        if(!repository.replace(value,claimed,clock.instant()))return;
        var blocked=claimed.release(ContractAnalysisCase.State.UNKNOWN,claimed.sources(),claimed.steps(),"OWNER_AUTHORITY_UNAVAILABLE");
        if(!repository.replace(claimed,blocked,clock.instant()))throw new IllegalStateException("OWNER_BLOCK_UNPROVEN");
        audit.success("CONTRACT_ANALYSIS_AUTHORITY_BLOCKED",null,"CONTRACT_ANALYSIS_CASE",value.id().toString(),null,null,
            Map.of("reasonCode","OWNER_AUTHORITY_UNAVAILABLE"),"账号权限已变化，自动分析停止");
    }
}
