package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** No filesystem, parser, AI call or long computation inside these transactions. */
@Service
public class BudgetMaterialTransactions {
    private final BudgetMaterialJobRepository jobs;
    private final BudgetReviewTaskRepository tasks;
    private final OperationAuditService audit;
    private final BudgetMaterialBatchRepository batches;
    public BudgetMaterialTransactions(BudgetMaterialJobRepository jobs,BudgetReviewTaskRepository tasks,OperationAuditService audit,BudgetMaterialBatchRepository batches) {
        this.jobs=jobs;this.tasks=tasks;this.audit=audit;this.batches=batches;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void start(BudgetMaterialJob job,BudgetReviewTask task,CurrentUser user) {
        tasks.save(task);jobs.insert(job);
        audit.success("BUDGET_MATERIAL_QUEUED",user,"BUDGET_REVIEW_TASK",task.id().toString(),task.fileName(),null,null,"预算材料已保存，等待后台处理");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void startInBatch(BudgetMaterialJob job,BudgetReviewTask task,UUID batchId,CurrentUser user) {
        tasks.save(task);jobs.insert(job);batches.attach(new BudgetMaterialBatch.Source(task.id(),batchId,null,null));
        audit.success("BUDGET_MATERIAL_QUEUED",user,"BUDGET_REVIEW_TASK",task.id().toString(),null,null,Map.of("batchId",batchId),"预算材料已保存，等待后台处理");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void extracted(UUID id,String token,String facts,int count,CurrentUser user) {
        jobs.requireLease(id,token);
        batches.extracted(id,facts);
        tasks.save(tasks.findById(id).orElseThrow().withStatus("PARSED",count,null));
        if(!jobs.complete(id,token,"PARSED",Instant.now()))throw new IllegalStateException("budget lease lost");
        audit.success("BUDGET_MATERIAL_EXTRACTED",user,"BUDGET_REVIEW_TASK",id.toString(),null,null,Map.of("rowCount",count),"保存预算材料提取依据");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claim(UUID id,String token,Instant now,Instant until) {
        if(!jobs.claim(id,token,now,until)) return false;
        // Do not erase committed review state when recovering after a process exit.
        var task=tasks.findById(id).orElseThrow();
        if(!Set.of("PARSED","REVIEWED","WAITING_CONFIRMATION").contains(task.status())) tasks.save(task.withStatus("PROCESSING",task.parsedLineCount(),null));
        return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void parsed(BudgetReviewTask task,String token) {jobs.requireLease(task.id(),token);tasks.save(task);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(UUID id,String token,String status,CurrentUser user) {
        finish(id,token,status,null,user);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(UUID id,String token,String status,String reason,CurrentUser user) {
        jobs.requireLease(id,token);
        if(!jobs.complete(id,token,status,Instant.now())) throw new IllegalStateException("budget lease lost");
        var task=tasks.findById(id).orElseThrow();
        if(!status.equals("PARSED")) tasks.save(task.withStatus(status,task.parsedLineCount(),reason==null?"BUDGET_PROCESSING_UNCONFIRMED":reason));
        audit.success("BUDGET_MATERIAL_"+status,user,"BUDGET_REVIEW_TASK",id.toString(),task.fileName(),null,Map.of("status",status),"保存预算材料后台处理结果");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void terminate(UUID id,String token,String reason,CurrentUser user) {
        new com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated(reason);
        jobs.requireLease(id,token);
        var task=tasks.findById(id).orElseThrow();
        if(task.parsedLineCount()!=0||!task.status().equals("PROCESSING"))throw new IllegalStateException("budget timeout already has results");
        if(!jobs.complete(id,token,"FAILED",Instant.now()))throw new IllegalStateException("budget lease lost");
        tasks.save(task.withStatus("FAILED",0,reason));
        audit.success("BUDGET_MATERIAL_TERMINATED",user,"BUDGET_REVIEW_TASK",id.toString(),null,null,Map.of("reasonCode",reason),"材料处理超时，未生成结果");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void cleaned(UUID id,String reference) {
        var task=tasks.findByIdForUpdate(id).orElseThrow();
        if(task.status().equals("FAILED")&&task.parsedLineCount()==0&&Objects.equals(reference,task.tempFilePath()))tasks.save(task.withoutStoredMaterial());
    }
}
