package com.fuquan.audit.common.material.application;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MaterialIntakeWorkerTest {
    private final MaterialIntakeRepository repository=mock(MaterialIntakeRepository.class);
    private final MaterialIntakeService service=mock(MaterialIntakeService.class);
    private final UserAccountRoleQueryPort users=mock(UserAccountRoleQueryPort.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"),ZoneOffset.UTC);
    private MaterialIntake queued() {
        return new MaterialIntake(UUID.randomUUID(),"owner",UUID.randomUUID().toString(),"材料.docx","application/pdf",10,
                "a".repeat(64),"b".repeat(64),"QUEUED","queued",clock.instant(),null,null,null,clock.instant(),clock.instant());
    }
    private void activeOwner() {
        var account=mock(UserAccount.class);
        when(account.roles()).thenReturn(Set.of("PROCUREMENT_APPLICANT"));
        when(account.toCurrentUser()).thenReturn(new CurrentUser("owner","owner","测试用户",Set.of("PROCUREMENT_APPLICANT")));
        when(users.findActiveById("owner")).thenReturn(Optional.of(account));
    }
    @Test void scannerHasBoundedParallelismAndAnExceptionInOneFileDoesNotStopTheNext() throws Exception {
        activeOwner();var rows=new CopyOnWriteArrayList<>(List.of(queued(),queued(),queued()));
        when(repository.pending(any(),anyInt())).thenAnswer(i -> List.copyOf(rows));
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var third=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i -> {int n=calls.incrementAndGet(); entered.countDown();
            if(n<=2) release.await(5,TimeUnit.SECONDS);else third.countDown();
            rows.removeIf(r -> r.id().equals(i.getArgument(0)));
            if(n==1) throw new IllegalStateException("one file DB failure");return null;
        }).when(service).processPending(any(),any(),any());
        try(var worker=new WorkerCloser(new MaterialIntakeWorker(repository,service,users,clock,2))) {
            worker.value.scan();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();worker.value.scan();assertThat(calls.get()).isEqualTo(2);
            release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(third.getCount()>0 && System.nanoTime()<deadline) {worker.value.scan();Thread.sleep(10);}
            assertThat(third.await(1,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();}
    }
    @Test void revokedOwnerIsRejectedWithoutModelWorkOrInventedRoles() throws Exception {
        var row=queued();when(repository.pending(any(),anyInt())).thenReturn(List.of(row));
        when(users.findActiveById("owner")).thenReturn(Optional.empty());
        var rejected=new CountDownLatch(1);doAnswer(i->{rejected.countDown();return null;}).when(service).rejectPendingOwner(row.id());
        try(var worker=new WorkerCloser(new MaterialIntakeWorker(repository,service,users,clock,1))) {
            worker.value.scan();assertThat(rejected.await(3,TimeUnit.SECONDS)).isTrue();verify(service,never()).processPending(any(),any(),any());
        }
    }
    private record WorkerCloser(MaterialIntakeWorker value) implements AutoCloseable {
        public void close(){value.close();}
    }
}
