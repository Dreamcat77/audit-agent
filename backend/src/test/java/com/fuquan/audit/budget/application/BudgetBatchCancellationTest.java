package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.budget.infra.memory.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BudgetBatchCancellationTest {
    @Test void cancelsParsedAndRunningMembersFencesLateCompletionAndClearsFacts() {
        var jobs=new InMemoryBudgetMaterialJobRepository();var tasks=new InMemoryBudgetReviewTaskRepository();
        var batches=new InMemoryBudgetMaterialBatchRepository(jobs,tasks);
        var items=mock(com.fuquan.audit.budget.application.port.BudgetReviewItemRepository.class);
        var audit=mock(OperationAuditService.class);
        var tx=new BudgetMaterialBatchTransactions(batches,tasks,items,audit);tx.setJobs(jobs);
        var now=Instant.now();var batchId=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        var ka=UUID.randomUUID();var kb=UUID.randomUUID();
        batches.insert(new BudgetMaterialBatch(batchId,"owner",null,null,List.of(new BudgetMaterialBatch.Member(ka,"a.pdf",10),new BudgetMaterialBatch.Member(kb,"b.pdf",10)),"OPEN",null,null,now));
        for(var id:List.of(a,b)){
            jobs.insert(new BudgetMaterialJob(id,"owner",(id.equals(a)?ka:kb).toString(),"b".repeat(64),id.equals(a)?"PARSED":"PROCESSING","old",now.plusSeconds(60),now));
            tasks.save(new BudgetReviewTask(id,id.equals(a)?"a.pdf":"b.pdf","application/pdf",10,"stored",id.equals(a)?"PARSED":"PROCESSING",3,null,now,now));
            batches.attach(new BudgetMaterialBatch.Source(id,batchId,"private facts","private result"));
        }
        assertThat(batches.claim(batchId,"batch-worker",now,now.plusSeconds(60))).isTrue();
        var user=new CurrentUser("owner","owner","owner",Set.of("CONTRACT_USER"));
        assertThat(tx.cancel(batchId,user)).containsExactlyInAnyOrder(a,b);
        assertThat(tx.cancel(batchId,user)).containsExactlyInAnyOrder(a,b);
        for(var id:List.of(a,b)){
            assertThat(tasks.findById(id).orElseThrow().errorDetail()).isEqualTo("USER_CANCELLED");
            assertThat(tasks.findById(id).orElseThrow().parsedLineCount()).isZero();
            assertThat(batches.source(id).orElseThrow().factsJson()).isNull();
            assertThat(batches.source(id).orElseThrow().resultJson()).isNull();
            assertThat(jobs.complete(id,"old","PARSED",now)).isFalse();
        }
        assertThatThrownBy(()->batches.requireLease(batchId,"batch-worker")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->batches.requireAcceptingUploads(batchId)).isInstanceOf(IllegalStateException.class);
        assertThat(batches.pending(now.plusSeconds(120),10)).isEmpty();
        assertThat(batches.countHistory("owner",com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(null,null,null))).isZero();
        assertThat(batches.countHistory(null,com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(null,null,null))).isZero();
        var service=new BudgetMaterialBatchService(batches,jobs,tasks,tx,mock(BudgetUploadedMaterialAnalyzer.class),new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(service.view(batchId,user).status()).isEqualTo("CANCELLED");
        assertThat(service.result(a).status()).isEqualTo("CANCELLED");
    }
}
