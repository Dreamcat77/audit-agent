package com.fuquan.audit.budget.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetBatchAdmissionTest {
    @Test void concurrentReadyNotificationsDeferTheOtherBatchWithoutClaimingOrFailingIt() throws Exception {
        var repository=mock(BudgetMaterialBatchRepository.class);
        var jobs=mock(BudgetMaterialJobRepository.class);var tasks=mock(BudgetReviewTaskRepository.class);
        var tx=mock(BudgetMaterialBatchTransactions.class);
        var service=new BudgetMaterialBatchService(repository,jobs,tasks,tx,new BudgetUploadedMaterialAnalyzer(),new ObjectMapper());
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        when(repository.find(first)).thenAnswer(inv->{entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return Optional.of(batch(first));});
        when(repository.find(second)).thenReturn(Optional.of(batch(second)));
        when(repository.sources(any())).thenReturn(List.of());
        try {
            var active=pool.submit(()->service.process(first));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            service.process(second);verify(repository,never()).find(second);verifyNoInteractions(tx);
            release.countDown();active.get(3,TimeUnit.SECONDS);
            service.process(second);verify(repository).find(second);verifyNoInteractions(tx);
        } finally {release.countDown();pool.shutdownNow();}
    }
    private BudgetMaterialBatch batch(UUID id) {
        return new BudgetMaterialBatch(id,"owner",null,null,List.of(new BudgetMaterialBatch.Member(UUID.randomUUID(),"说明.txt",10)),"OPEN",null,null,Instant.now());
    }
}
