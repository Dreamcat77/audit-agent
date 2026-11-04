package com.fuquan.audit.contract.application.debug;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.infra.memory.debug.InMemorySanitizedDebugBundleRepository;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.debug.DebugManifestBuilderTest.*;

class SanitizedDebugBundleServiceTest {
    @TempDir Path directory;
    final CurrentUser actor=new CurrentUser("00000000-0000-0000-0000-000000000004","auditor","auditor",Set.of("AUDITOR"));
    final ProjectRequestMetadata meta=new ProjectRequestMetadata("synthetic-request","synthetic-trace",null);
    final List<OperationAuditEvent> events=new ArrayList<>();
    final InMemoryIdempotencyAdapter idempotency=new InMemoryIdempotencyAdapter();
    InMemorySanitizedDebugBundleRepository repository;
    ContractReviewRunService reads;
    ContractReviewRunRepository runs;
    FileStoragePort storage;
    SanitizedDebugBundleService service;
    @BeforeEach void setup() {
        repository=spy(new InMemorySanitizedDebugBundleRepository());
        reads=mock(ContractReviewRunService.class); runs=mock(ContractReviewRunRepository.class);
        when(reads.get(PROJECT,CONTRACT,RUN)).thenReturn(source());
        when(runs.findByIdForUpdate(RUN)).thenReturn(Optional.of(source().run()));
        when(runs.findById(RUN)).thenReturn(Optional.of(source().run()));
        storage=spy(new FileSystemStorageAdapter(directory,(source,target) -> Files.createLink(target,source)));
        wire(events::add);
    }
    void wire(OperationAuditPort audit) {
        var tx=new DebugBundleTransactions(repository,runs,reads,idempotency,audit,new DebugManifestBuilder(),Clock.fixed(AT,ZoneOffset.UTC));
        service=new SanitizedDebugBundleService(() -> Optional.of(actor),repository,tx,storage);
    }
    SanitizedDebugBundle generate() { return service.generate(scope(),DebugContentLevel.METADATA_ONLY,"same",meta); }
    @Test void sameKeyReplaysFrozenArtifactWithoutReadingLaterRun() {
        var first=generate(); reset(reads);
        var second=generate();
        assertThat(second.bundleId()).isEqualTo(first.bundleId());
        assertThat(second.status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
        assertThat(repository.findByRunId(RUN)).hasSize(1);
        verify(reads,never()).get(any(),any(),any());
        assertThat(new String(service.download(scope(),first.bundleId(),meta),java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("SECRET_CANONICAL_BODY","provider-secret");
        assertThat(events).extracting(OperationAuditEvent::action).contains("GENERATE_START","GENERATE_COMPLETE","DOWNLOAD");
        assertThat(events).allMatch(e -> e.taskId()==null);
    }
    @Test void wrongOwnerCannotReadOrDownload() {
        var bundle=generate(); var wrong=new DebugScope(UUID.randomUUID(),CONTRACT,RUN,null,null);
        assertThatThrownBy(() -> service.get(wrong,bundle.bundleId(),meta)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.download(wrong,bundle.bundleId(),meta)).isInstanceOf(BusinessException.class);
    }
    @Test void changedScopeWithSameKeyConflictsBeforeStorage() {
        generate(); var changed=new DebugScope(PROJECT,CONTRACT,RUN,UUID.randomUUID(),null);
        assertThatThrownBy(() -> service.generate(changed,DebugContentLevel.METADATA_ONLY,"same",meta)).isInstanceOf(BusinessException.class);
    }
    @Test void storageUnknownDoesNotPublishAndExplicitSameKeyRecovers() {
        doReturn(StoredFileInspection.unknown()).when(storage).inspect(any());
        var pending=generate(); assertThat(pending.status()).isEqualTo(SanitizedDebugBundle.Status.UNKNOWN);
        verify(storage,never()).stage(any(),anyLong());
        doCallRealMethod().when(storage).inspect(any());
        assertThat(generate().bundleId()).isEqualTo(pending.bundleId());
        assertThat(generate().status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
    }
    @Test void publishedBytesSurviveCompletionDatabaseTimeoutAndReopenSameIdentity() {
        doAnswer(invocation -> {
            SanitizedDebugBundle next=invocation.getArgument(1);
            if(next.status()==SanitizedDebugBundle.Status.AVAILABLE) throw new IllegalStateException("synthetic database timeout");
            return invocation.callRealMethod();
        }).when(repository).transition(any(),any());
        var unknown=generate(); assertThat(unknown.status()).isEqualTo(SanitizedDebugBundle.Status.UNKNOWN);
        assertThat(storage.inspect(new StorageKey(unknown.storageKey())).status()).isEqualTo(StoredFileInspection.Status.PRESENT);
        doCallRealMethod().when(repository).transition(any(),any());
        var recovered=generate(); assertThat(recovered.bundleId()).isEqualTo(unknown.bundleId());
        assertThat(recovered.status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
        verify(storage,times(1)).stage(any(),anyLong());
    }
    @Test void getUnknownDoesNotRegenerateOrReconcile() {
        doReturn(StoredFileInspection.unknown()).when(storage).inspect(any());
        var unknown=generate(); clearInvocations(storage);
        assertThat(service.get(scope(),unknown.bundleId(),meta).status()).isEqualTo(SanitizedDebugBundle.Status.UNKNOWN);
        verifyNoInteractions(storage);
    }
    @Test void auditFailureDeniesDownload() {
        var ready=generate(); wire(event -> { throw new IllegalStateException("audit unavailable"); });
        assertThatThrownBy(() -> service.download(scope(),ready.bundleId(),meta)).isInstanceOf(RuntimeException.class);
    }
    @Test void unavailableRedactedLevelCreatesNoDurableResource() {
        assertThatThrownBy(() -> service.generate(scope(),DebugContentLevel.CUSTOMER_POLICY_APPROVED_REDACTED,"key",meta)).isInstanceOf(BusinessException.class);
        assertThat(repository.findByRunId(RUN)).isEmpty();
    }
    @Test void staleClosureCannotDowngradeAvailable() {
        var ready=generate();
        assertThatThrownBy(() -> repository.transition(ready,ready.withStatus(SanitizedDebugBundle.Status.UNKNOWN)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void emptyListChecksOwnershipAndAuditsAccess() {
        assertThat(service.list(scope(),meta)).isEmpty();
        assertThat(events).extracting(OperationAuditEvent::action).contains("LIST");
        assertThatThrownBy(() -> service.list(new DebugScope(UUID.randomUUID(),CONTRACT,RUN,null,null),meta))
                .isInstanceOf(BusinessException.class);
    }
    @Test void manifestFailureDoesNotLeaveLocalOrphanClaim() {
        var manifests=mock(DebugManifestBuilder.class);
        when(manifests.build(any(),any(),any(),any(),any())).thenThrow(new IllegalArgumentException("synthetic rejected projection"));
        var tx=new DebugBundleTransactions(repository,runs,reads,idempotency,events::add,manifests,Clock.fixed(AT,ZoneOffset.UTC));
        assertThatThrownBy(() -> tx.start(scope(),DebugContentLevel.METADATA_ONLY,"same",actor,meta)).isInstanceOf(IllegalArgumentException.class);
        assertThat(idempotency.findByScopeAndKey(DebugBundleTransactions.SCOPE,"same")).isEmpty();
        assertThat(generate().status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
    }
    @Test void concurrentOriginalKeyRecoveryKeepsOneArtifactIdentity() throws Exception {
        doReturn(StoredFileInspection.unknown()).when(storage).inspect(any());
        var pending=generate();
        doCallRealMethod().when(storage).inspect(any());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results=pool.invokeAll(List.<java.util.concurrent.Callable<SanitizedDebugBundle>>of(this::generate,this::generate));
            for(var result:results) assertThat(result.get().bundleId()).isEqualTo(pending.bundleId());
            assertThat(generate().status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
            assertThat(repository.findByRunId(RUN)).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void springProxySuspendsCallerTransactionAcrossStorageAndUsesShortClosures() {
        var manager=new RecordingTransactions();
        var tx=proxied(new DebugBundleTransactions(repository,runs,reads,idempotency,event -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            events.add(event);
        },new DebugManifestBuilder(),Clock.fixed(AT,ZoneOffset.UTC)),manager);
        service=proxied(new SanitizedDebugBundleService(() -> Optional.of(actor),repository,tx,storage),manager);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(storage).inspect(any());
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
            assertThat(generate().status()).isEqualTo(SanitizedDebugBundle.Status.AVAILABLE);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });
        assertThat(manager.commits).isEqualTo(3); // START, COMPLETE, resumed caller
        assertThat(manager.suspensions).isEqualTo(1);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target,RecordingTransactions manager) {
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T)proxy.getProxy();
    }
    private static final class RecordingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active=new ThreadLocal<>();
        int commits,suspensions;
        protected Object doGetTransaction() { return new Object(); }
        protected boolean isExistingTransaction(Object tx) { return Boolean.TRUE.equals(active.get()); }
        protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition) { active.set(true); }
        protected Object doSuspend(Object tx) { active.remove();suspensions++;return Boolean.TRUE; }
        protected void doResume(Object tx,Object suspended) { active.set(true); }
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        protected void doCleanupAfterCompletion(Object tx) { active.remove(); }
    }
}
