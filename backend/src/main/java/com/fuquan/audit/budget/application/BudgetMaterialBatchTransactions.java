package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Source snapshots, file results and batch completion commit under a fenced short transaction. */
@Service
public class BudgetMaterialBatchTransactions {
    private final BudgetMaterialBatchRepository batches;
    private BudgetMaterialJobRepository jobs;
    @org.springframework.beans.factory.annotation.Autowired
    public void setJobs(BudgetMaterialJobRepository value){jobs=value;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<UUID> cancel(UUID id,CurrentUser user) {
        var batch=batches.find(id).orElseThrow(()->new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        if(!user.roles().contains("SYSTEM_ADMIN")&&!batch.actorId().equals(user.userId()))throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND);
        if("USER_CANCELLED".equals(batch.leaseToken()))return batches.sources(id).stream().map(BudgetMaterialBatch.Source::taskId).toList();
        if(!batches.cancel(id))throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT,"当前任务已结束，无法取消");
        var ids=new ArrayList<UUID>();
        for(var source:batches.sources(id)) {
            UUID taskId=source.taskId();ids.add(taskId);
            jobs.cancel(taskId,Instant.now());
            var task=tasks.findByIdForUpdate(taskId).orElseThrow();
            for(var item:items.findByTaskId(taskId))items.deleteByTaskAndRow(taskId,item.rowNumber());
            tasks.save(task.cancelled());
        }
        batches.clearCancelledSources(id);
        audit.success("BUDGET_BATCH_CANCELLED",user,"BUDGET_MATERIAL_BATCH",id.toString(),null,null,Map.of("memberCount",ids.size()),"取消预算材料处理");
        return ids;
    }
    private final BudgetReviewTaskRepository tasks;
    private final BudgetReviewItemRepository items;
    private final OperationAuditService audit;
    public BudgetMaterialBatchTransactions(BudgetMaterialBatchRepository batches,BudgetReviewTaskRepository tasks,BudgetReviewItemRepository items,OperationAuditService audit) {
        this.batches=batches;this.tasks=tasks;this.items=items;this.audit=audit;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void register(BudgetMaterialBatch batch,CurrentUser user) {
        batches.insert(batch);
        audit.success("BUDGET_BATCH_REGISTERED",user,"BUDGET_MATERIAL_BATCH",batch.id().toString(),null,null,Map.of("memberCount",batch.members().size()),"保存预算上传清单");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claim(UUID id,String token,Instant now,Instant until){return batches.claim(id,token,now,until);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(UUID id,String token,Map<UUID,BudgetUploadedFacts.FileResult> results,Map<UUID,String> encoded) {
        batches.requireLease(id,token);
        var sources=batches.sources(id);
        if(!new HashSet<>(sources.stream().map(BudgetMaterialBatch.Source::taskId).toList()).equals(results.keySet())||!encoded.keySet().equals(results.keySet()))throw new IllegalStateException("budget result membership mismatch");
        for(var entry:results.entrySet()) {
            var task=tasks.findById(entry.getKey()).orElseThrow();var rows=new ArrayList<BudgetReviewItem>();int row=0;
            if(!items.findByTaskId(task.id()).isEmpty())throw new IllegalStateException("budget reference already has legacy results");
            for(var line:entry.getValue().lines()) {
                boolean within=line.status().equals("WITHIN_APPROVAL"),over=line.status().equals("OVER_APPROVAL");
                rows.add(new BudgetReviewItem(UUID.randomUUID(),task.id(),++row,legacyText(line.subjectCode(),64),legacyText(line.subject(),800),line.declaredAmount(),null,legacyText(line.projectCode(),64),
                    line.approvalAmount()==null?null:legacyText(line.subjectCode(),64),line.approvalAmount()==null?null:legacyText(line.subject(),800),line.approvalAmount(),null,
                    line.approvalAmount()==null?"UNMATCHED":"MATCHED","UPLOADED_MATERIAL",null,
                    within?"WITHIN_BUDGET":over?"OVER_BUDGET":"NOT_CHECKED",
                    line.declaredAmount()==null||line.approvalAmount()==null?null:line.declaredAmount().subtract(line.approvalAmount()),
                    within?null:line.status(),within?null:line.opinion(),within?"INFO":over?"CRITICAL":"WARNING",null,null,null,
                    "NOT_CHECKED","NOT_CHECKED","NOT_CHECKED",null,"NOT_CHECKED","NOT_CHECKED","NOT_CHECKED",within?List.of():List.of(line.opinion()),Instant.now()));
            }
            items.saveAll(rows);batches.result(task.id(),encoded.get(task.id()));
            if(task.status().equals("PARSED"))tasks.save(task.withStatus("REVIEWED",rows.size(),null));
        }
        batches.complete(id,token);
        audit.success("BUDGET_BATCH_REVIEWED",null,"BUDGET_MATERIAL_BATCH",id.toString(),null,null,Map.of("memberCount",results.size()),"保存上传材料预算参考结果");
    }
    /** Historical narrow index columns cannot truncate source facts. The full value remains in both CLOB snapshots. */
    private static String legacyText(String value,int bytes) {return value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>bytes?null:value;}
}
