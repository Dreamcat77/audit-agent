package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fuquan.audit.security.domain.CurrentUser;

class AuditCaseUnexpectedFailureTest {
    @Test void cancellationCannotHideAnActualDatabaseFailure() {
        var repo=new InMemoryAuditCaseRepository();var tx=mock(AuditCaseTransactions.class);
        var registry=new com.fuquan.audit.common.application.TaskCancellationRegistry();
        var now=Instant.now();var queued=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"b".repeat(64),"QUEUED",1,"upload",now.plusSeconds(3600),0,now,List.of(),null);
        repo.insert(queued);
        when(tx.save(any(),any(),any())).thenAnswer(call->{
            AuditCase previous=call.getArgument(0),next=call.getArgument(1);
            if(previous.status().equals("RUNNING")) {
                repo.replace(previous,previous.cancel());registry.interrupt(previous.id());
                throw new org.springframework.dao.DataIntegrityViolationException("synthetic storage rejection during cancellation");
            }
            return repo.replace(previous,next);
        });
        var service=new AuditCaseService(repo,tx,null,null,null,Clock.systemUTC());
        var user=new CurrentUser("owner","test","test",Set.of("AUDITOR"));
        try(var registration=registry.register(queued.id())) {
            assertThatThrownBy(()->service.process(queued.id(),user)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {Thread.interrupted();}
        assertThat(repo.find(queued.id()).orElseThrow().cancelled()).isTrue();
    }
    @Test void workerStorageExceptionIsClosedThroughANewCheckpoint() {
        var repo=new InMemoryAuditCaseRepository();var tx=mock(AuditCaseTransactions.class);
        when(tx.save(any(),any(),any())).thenAnswer(call->{
            AuditCase previous=call.getArgument(0),next=call.getArgument(1);
            if(previous.status().equals("RUNNING")&&!next.status().equals("UNKNOWN"))throw new org.springframework.dao.DataIntegrityViolationException("synthetic storage rejection");
            return repo.replace(previous,next);
        });
        var service=new AuditCaseService(repo,tx,null,null,null,Clock.systemUTC());var now=Instant.now();
        var queued=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"b".repeat(64),"QUEUED",1,"upload",now.plusSeconds(3600),0,now,List.of(),null);
        repo.insert(queued);var user=new CurrentUser("owner","test","test",Set.of("AUDITOR"));
        assertThatThrownBy(()->service.process(queued.id(),user)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repo.find(queued.id()).orElseThrow().status()).isEqualTo("UNKNOWN");
    }
    @Test void failedCheckpointClosesOwnClaimAndPreservesDurableFiles() {
        var repo=new InMemoryAuditCaseRepository();var tx=mock(AuditCaseTransactions.class);
        when(tx.save(any(),any(),any())).thenAnswer(call->repo.replace(call.getArgument(0),call.getArgument(1)));
        var service=new AuditCaseService(repo,tx,null,null,null,Clock.systemUTC());
        var doc=new AuditCase.Document(UUID.randomUUID(),"测试.pdf",1,"a".repeat(64),"storage","EXTRACTED",List.of(),List.of(),"PDF",null,1);
        var now=Instant.now();var running=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"b".repeat(64),"RUNNING",1,"claim",now.plusSeconds(3600),1,now,List.of(doc),null);
        repo.insert(running);service.recordUnexpectedFailure(running.id(),"claim",null);
        var closed=repo.find(running.id()).orElseThrow();assertThat(closed.status()).isEqualTo("UNKNOWN");assertThat(closed.documents()).containsExactly(doc);
    }
    @Test void closeoutCannotOverwriteCancellationAnotherLeaseOrCompletedReport() {
        var repo=new InMemoryAuditCaseRepository();var tx=mock(AuditCaseTransactions.class);
        var service=new AuditCaseService(repo,tx,null,null,null,Clock.systemUTC());var now=Instant.now();
        var running=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"b".repeat(64),"RUNNING",1,"new-claim",now.plusSeconds(3600),1,now,List.of(),null);
        repo.insert(running);service.recordUnexpectedFailure(running.id(),"old-claim",null);
        var cancelled=running.cancel();repo.replace(running,cancelled);service.recordUnexpectedFailure(running.id(),"new-claim",null);
        verify(tx,never()).save(any(),any(),any());assertThat(repo.find(running.id()).orElseThrow()).isEqualTo(cancelled);
        var completed=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"b".repeat(64),"COMPLETED",1,"claim",now,1,now,List.of(),null);
        repo.insert(completed);service.recordUnexpectedFailure(completed.id(),"claim",null);verify(tx,never()).save(any(),any(),any());
    }
}
