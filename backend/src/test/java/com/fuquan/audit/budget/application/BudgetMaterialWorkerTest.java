package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BudgetMaterialWorkerTest {
    @Test void revokedOwnerIsRemovedFromPendingWorkWithoutModelCalls() throws Exception {
        var jobs=mock(BudgetMaterialJobRepository.class);var service=mock(BudgetMaterialService.class);var users=mock(UserAccountRoleQueryPort.class);
        var j=new BudgetMaterialJob(UUID.randomUUID(),"inactive-owner",UUID.randomUUID().toString(),"a".repeat(64),"QUEUED",null,null,Instant.now());
        when(jobs.pending(any(),anyInt())).thenReturn(List.of(j));when(users.findActiveById(j.actorId())).thenReturn(Optional.empty());
        var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(service).rejectOwner(j.id());
        var worker=new BudgetMaterialWorker(jobs,service,users);
        try {worker.scan();assertThat(done.await(3,TimeUnit.SECONDS)).isTrue();verify(service,never()).process(any(),any(),any());}
        finally {worker.close();}
    }
    @Test void fencedReviewCommitRejectsStaleWorkBeforeAnyResultWrites() {
        var tasks=mock(com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository.class);
        var items=mock(com.fuquan.audit.budget.application.port.BudgetReviewItemRepository.class);
        var coordinator=new BudgetReviewTransactionCoordinator(tasks,items,null,null,null,null,null);
        var task=new com.fuquan.audit.budget.domain.BudgetReviewTask(UUID.randomUUID(),"test.xlsx",null,1,"ref","PARSED",1,null,Instant.now(),Instant.now());
        assertThatThrownBy(()->coordinator.saveProgress(task,List.of(),()->{throw new IllegalStateException("lost");})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->coordinator.fail(task,"failure",()->{throw new IllegalStateException("lost");})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tasks,items);
    }
}
