package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.closeout.port.ReportRendererPort;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportArtifactTest {
    @Test void renderingAndPublicationObserveCommittedStartOutsideSpringTransaction() {
        var f = new CloseoutFixtures();
        var events = new java.util.ArrayList<String>();
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { events.add("begin"); }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { events.add("commit"); }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { events.add("rollback"); }
        };
        var factory = new org.springframework.aop.framework.ProxyFactory(transactions(f)); factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var tx = (ReportTransactionCoordinator) factory.getProxy();
        ReportRendererPort renderer = json -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(List.of("begin", "commit"), events);
            assertEquals(ContractReviewReport.Status.PENDING, f.closeout.findReportsByRun(f.runId).get(0).status());
            return new DeterministicHtmlReportRenderer().render(json);
        };
        var store = spy(storage());
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(ContractReviewReport.Status.GENERATING, f.closeout.findReportsByRun(f.runId).get(0).status());
            return invocation.callRealMethod();
        }).when(store).promote(any(), any());
        var report = generate(f, service(f, tx, store, renderer), "boundaries");
        assertEquals(ContractReviewReport.Status.AVAILABLE, report.status());
        assertEquals(List.of("begin", "commit", "begin", "commit", "begin", "commit"), events);
    }
    @Test void availableVersionRejectsRepositoryOverwrite() {
        var f = new CloseoutFixtures(); var s = service(f, transactions(f), storage(), new DeterministicHtmlReportRenderer());
        var saved = generate(f, s, "immutable");
        var malicious = new ContractReviewReport(saved.reportId(), saved.reviewRunId(), saved.reportVersion(), saved.reportSchemaVersion(),
                ContractReviewReport.Status.GENERATING, saved.inputSnapshotIdentity(), saved.findingSnapshotIdentity(),
                saved.acknowledgementSnapshotIdentity(), "tampered", saved.artifactStorageKey(), saved.artifactMediaType(),
                saved.artifactSize(), saved.sha256(), saved.generatedBy(), saved.requestedAt(), saved.generatedAt(),
                saved.failedAt(), saved.unknownAt(), null, saved.requestId(), saved.traceId(), saved.versionNo() + 1);
        assertThrows(IllegalArgumentException.class, () -> f.closeout.compareAndSetReport(saved, malicious));
        assertEquals(saved, f.closeout.findReport(saved.reportId()).orElseThrow());
    }
    @TempDir Path root;
    ReportTransactionCoordinator transactions(CloseoutFixtures f) {
        return new ReportTransactionCoordinator(f.runs, f.closeout, f.idempotency,
                new ReportSnapshotFactory(f.outputs, f.frozen, f.closeout, f.f02Acks), f.audit::add, f.clock);
    }
    ContractReviewReportService service(CloseoutFixtures f, ReportTransactionCoordinator tx, FileStoragePort storage, ReportRendererPort renderer) {
        return new ContractReviewReportService(f.auth, f.runs, f.closeout, tx, renderer, storage);
    }
    FileSystemStorageAdapter storage() { return new FileSystemStorageAdapter(root, (source, target) -> Files.createLink(target, source)); }
    ContractReviewReport generate(CloseoutFixtures f, ContractReviewReportService s, String key) {
        return s.generate(f.project, f.contract, f.runId, key, f.metadata);
    }
    @Test void publishesRealCreateOnlyHtmlAndReopensOldVersionAfterAcknowledgement() {
        var f = new CloseoutFixtures(); var renderer = spy(new DeterministicHtmlReportRenderer());
        var s = service(f, transactions(f), storage(), renderer);
        var one = generate(f, s, "report-1");
        assertEquals(ContractReviewReport.Status.AVAILABLE, one.status()); assertEquals(1, one.reportVersion());
        byte[] old = s.download(f.project, f.contract, f.runId, one.reportId(), f.metadata).bytes();
        new AcknowledgementTransactionCoordinator(new CloseoutOwnership(f.outputs, f.auth), f.closeout, f.idempotency, f.audit::add, f.clock)
                .create(f.project, f.contract, f.runId, f.finding.findingId(), 0, "已沟通", "ack", f.actor.userId(), f.metadata);
        assertEquals(one, generate(f, s, "report-1"));
        var two = generate(f, s, "report-2");
        assertEquals(2, two.reportVersion()); assertNotEquals(one.acknowledgementSnapshotIdentity(), two.acknowledgementSnapshotIdentity());
        assertArrayEquals(old, s.download(f.project, f.contract, f.runId, one.reportId(), f.metadata).bytes());
        assertEquals(2, s.list(f.project, f.contract, f.runId).size());
        assertTrue(f.audit.stream().filter(a -> a.action().equals("DOWNLOAD")).allMatch(a -> a.taskId() == null));
    }
    @Test void storageUnknownKeepsSameOperationUntilMissingCanBeProven() {
        var f = new CloseoutFixtures(); var storage = spy(storage());
        doReturn(StoredFileInspection.unknown()).when(storage).inspect(any());
        var s = service(f, transactions(f), storage, new DeterministicHtmlReportRenderer());
        var unknown = generate(f, s, "uncertain");
        assertEquals(ContractReviewReport.Status.UNKNOWN, unknown.status());
        assertEquals(com.fuquan.audit.common.application.port.IdempotencyStatus.IN_PROGRESS,
                f.idempotency.findByScopeAndKey(ReportTransactionCoordinator.SCOPE, "uncertain").orElseThrow().status());
        verify(storage, never()).promote(any(), any());
        doCallRealMethod().when(storage).inspect(any());
        var recovered = generate(f, s, "uncertain");
        assertEquals(unknown.reportId(), recovered.reportId()); assertEquals(ContractReviewReport.Status.AVAILABLE, recovered.status());
        assertEquals(1, f.closeout.findReportsByRun(f.runId).size());
    }
    @Test void publishedBytesAndCompleteDatabaseTimeoutRecoverWithoutRenderingAgain() {
        var f = new CloseoutFixtures(); var tx = spy(transactions(f)); var renderer = spy(new DeterministicHtmlReportRenderer());
        doThrow(new IllegalStateException("simulated DB timeout")).when(tx).complete(any(), any());
        var store = storage(); var s = service(f, tx, store, renderer);
        var unknown = generate(f, s, "db-timeout");
        assertEquals(ContractReviewReport.Status.UNKNOWN, unknown.status());
        assertEquals(StoredFileInspection.Status.PRESENT, store.inspect(new StorageKey(unknown.artifactStorageKey())).status());
        doCallRealMethod().when(tx).complete(any(), any());
        var recovered = generate(f, s, "db-timeout");
        assertEquals(unknown.reportId(), recovered.reportId()); assertEquals(ContractReviewReport.Status.AVAILABLE, recovered.status());
        verify(renderer, times(1)).render(any());
    }
    @Test void twoConcurrentReportRequestsAllocateDistinctVersions() throws Exception {
        var f = new CloseoutFixtures(); var s = service(f, transactions(f), storage(), new DeterministicHtmlReportRenderer());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> generate(f, s, "a")); var b = pool.submit(() -> generate(f, s, "b"));
            assertEquals(Set.of(1, 2), Set.of(a.get().reportVersion(), b.get().reportVersion()));
            assertEquals(2, f.closeout.findReportsByRun(f.runId).size());
        } finally { pool.shutdownNow(); }
    }
    @Test void getAndListNeverRenderOrPublishAndWrongOwnerCannotDownload() {
        var f = new CloseoutFixtures(); var renderer = spy(new DeterministicHtmlReportRenderer()); var storage = spy(storage());
        var s = service(f, transactions(f), storage, renderer); var report = generate(f, s, "read");
        clearInvocations(renderer, storage);
        assertEquals(report, s.get(f.project, f.contract, f.runId, report.reportId()));
        assertEquals(List.of(report), s.list(f.project, f.contract, f.runId));
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                () -> s.download(UUID.randomUUID(), f.contract, f.runId, report.reportId(), f.metadata));
        verifyNoInteractions(renderer, storage);
    }
    @Test void anotherUserCannotReadDownloadOrGenerateTheRunOwnersReport() {
        var f = new CloseoutFixtures(); var s = service(f, transactions(f), storage(), new DeterministicHtmlReportRenderer());
        var report = generate(f, s, "owner-report");
        when(f.auth.currentUser()).thenReturn(Optional.of(new com.fuquan.audit.security.domain.CurrentUser(
                "another", "other", "另一人员", Set.of("AUDITOR"))));
        for (Runnable access : List.<Runnable>of(
                () -> s.list(f.project, f.contract, f.runId),
                () -> s.get(f.project, f.contract, f.runId, report.reportId()),
                () -> s.download(f.project, f.contract, f.runId, report.reportId(), f.metadata),
                () -> generate(f, s, "other-report"))) {
            var error = assertThrows(com.fuquan.audit.common.exception.BusinessException.class, access::run);
            assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND, error.errorCode());
        }
        assertEquals(1, f.closeout.findReportsByRun(f.runId).size());
        assertTrue(f.idempotency.findByScopeAndKey(ReportTransactionCoordinator.SCOPE, "other-report").isEmpty());
    }
    @Test void mismatchingPublishedBytesStayUnknownAndCannotDownload() {
        var f = new CloseoutFixtures(); var store = spy(storage());
        doReturn(StoredFileInspection.present(new StoredFileInfo(99, "b".repeat(64)))).when(store).inspect(any());
        var s = service(f, transactions(f), store, new DeterministicHtmlReportRenderer());
        var r = generate(f, s, "mismatch");
        assertEquals(ContractReviewReport.Status.UNKNOWN, r.status());
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                () -> s.download(f.project, f.contract, f.runId, r.reportId(), f.metadata));
        verify(store, never()).promote(any(), any());
    }
    @Test void renderFailureIsProvenFailedWithoutStorageWrite() {
        var f = new CloseoutFixtures(); var store = spy(storage());
        var s = service(f, transactions(f), store, json -> { throw new IllegalArgumentException("bad renderer"); });
        assertEquals(ContractReviewReport.Status.FAILED, generate(f, s, "render-fail").status());
        verifyNoInteractions(store);
    }
}
