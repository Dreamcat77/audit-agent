package com.fuquan.audit.common.material.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialContainerPort;
import com.fuquan.audit.integration.material.memory.InMemoryMaterialIntakeRepository;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;

class MaterialIntakeServiceTest {
    @Test void sharedOcrBusyRequeuesSameOperationWithoutModelAndLaterCompletes() throws Exception {
        var reader=mock(com.fuquan.audit.common.material.application.port.DocumentTextPort.class);
        var busy=new com.fuquan.audit.review.application.port.OcrCapacityUnavailableException();
        when(reader.read(any(),any(),any(),anyLong(),anyInt(),anyInt())).thenThrow(busy).thenReturn(
            new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(
                List.of(new MaterialFieldAiPort.Segment("p1","项目名称：设备更新；第三列不能丢失")),"OCR",
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
        service=new MaterialIntakeService(repository,new MaterialIntakeTransactions(repository,audit),storage,reader,
            new MaterialFieldNormalizer(),ai,clock,1,20971520,100,48000,900);
        var key=UUID.randomUUID().toString();var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),key,user);
        service.processPending(queued.id(),user,r->{});
        var saved=service.find(queued.id());assertThat(saved.status()).isEqualTo("QUEUED");assertThat(saved.operationKey()).isEqualTo(key);
        assertThat(saved.fields()).isNull();verifyNoInteractions(ai);verify(storage,never()).delete(any());
        service.processPending(queued.id(),user,r->{});
        assertThat(service.find(queued.id()).status()).isEqualTo("PARSED");
        assertThat(service.find(queued.id()).id()).isEqualTo(queued.id());
        verify(ai,times(1)).extract(any(),any(),any(),any());
    }
    @Test void timedOutFileIsDiscardedAfterCommitDoesNotReappearOrRetryAndNextFileSucceeds() {
        doThrow(new com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated("MATERIAL_CALL_TIMEOUT"))
            .doAnswer(i->{List<MaterialFieldAiPort.Segment>s=i.getArgument(3);return new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("r",List.of(new MaterialFieldAiPort.Fact("projectName","设备更新",s.get(0).locator(),s.get(0).text())))),"test","v6");})
            .when(ai).extract(anyString(),anyString(),anyString(),anyList());
        var failed=upload(UUID.randomUUID().toString());
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.fields()).isNull();assertThat(failed.timedOut()).isTrue();
        verify(storage).delete(new StorageKey(failed.storageKey()));
        assertThat(service.recent(user)).doesNotContain(failed);
        assertThat(service.retry(failed.id(),user).status()).isEqualTo("FAILED");
        assertThat(service.retryAsync(failed.id(),user).status()).isEqualTo("FAILED");
        assertThat(upload(UUID.randomUUID().toString()).status()).isEqualTo("PARSED");
        verify(ai,times(2)).extract(anyString(),anyString(),anyString(),anyList());
    }
    @Test void failedCleanupIsRediscoveredAndCannotDeleteSuccessfulFiles() {
        doThrow(new com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated("MATERIAL_TIME_LIMIT")).when(ai).extract(anyString(),anyString(),anyString(),anyList());
        doThrow(new IllegalStateException("synthetic cleanup interruption")).doNothing().when(storage).delete(any());
        var failed=upload(UUID.randomUUID().toString());
        assertThat(repository.timedOutForCleanup(10)).hasSize(1);
        service.cleanupTimedOut();assertThat(repository.timedOutForCleanup(10)).isEmpty();
        verify(storage,times(2)).delete(new StorageKey(failed.storageKey()));
    }
    @Test void uncertainModelOutcomeRemainsUnknownRatherThanKnownParseFailure() {
        doThrow(new com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown("CALL_OUTCOME_UNKNOWN"))
            .when(ai).extract(anyString(),anyString(),anyString(),anyList());
        var record=upload(UUID.randomUUID().toString());
        assertThat(record.status()).isEqualTo("UNKNOWN");
        assertThat(record.errorCode()).isEqualTo("AI_CALL_OUTCOME_UNKNOWN");
    }
    private final InMemoryMaterialIntakeRepository repository = new InMemoryMaterialIntakeRepository();
    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final MaterialContainerPort guard = mock(MaterialContainerPort.class);
    private final MaterialFieldAiPort ai = mock(MaterialFieldAiPort.class);
    private final OperationAuditService audit = mock(OperationAuditService.class);
    private final CurrentUser user = new CurrentUser("u", "user", "用户", Set.of("PROCUREMENT_APPLICANT"));
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
    private MaterialIntakeService service;
    private byte[] bytes;
    @BeforeEach void setup() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("项目名称：设备更新");
            var row = document.createTable(1, 3).getRow(0);
            row.getCell(0).setText("申请部门"); row.getCell(1).setText("信息中心"); row.getCell(2).setText("第三列不能丢失");
            document.write(output); bytes = output.toByteArray();
        }
        String fixtureSha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(storage.stage(any(), anyLong())).thenReturn(new StagedFile("stage", bytes.length, fixtureSha, new byte[]{80,75,3,4}));
        when(storage.inspect(any())).thenReturn(StoredFileInspection.present(new StoredFileInfo(bytes.length, fixtureSha)));
        doAnswer(i -> new StoredFileContent(new ByteArrayInputStream(bytes), bytes.length)).when(storage).open(any());
        when(guard.validate(any())).thenReturn(bytes);
        when(ai.extract(anyString(), anyString(), anyString(), anyList())).thenAnswer(i -> {
            List<MaterialFieldAiPort.Segment> segments = i.getArgument(3);
            assertThat(segments.stream().map(MaterialFieldAiPort.Segment::text).toList()).anyMatch(t -> t.contains("第三列不能丢失"));
            var segment = segments.get(0);
            return new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one", List.of(
                new MaterialFieldAiPort.Fact("projectName", "设备更新", segment.locator(), segment.text())))), "model", "v1");
        });
        service = new MaterialIntakeService(repository, new MaterialIntakeTransactions(repository, audit), storage,
                new com.fuquan.audit.integration.material.OfficeDocumentTextReader(guard,mock(OcrPort.class)),
                new MaterialFieldNormalizer(), ai, clock, 1, 20971520, 100, 48000, 900);
    }
    private com.fuquan.audit.common.material.domain.MaterialIntake upload(String key) {
        return service.upload("材料.docx", new ByteArrayInputStream(bytes), key, user);
    }
    @Test void shutdownInterruptionDuringModelFailureLeavesProcessingForLeaseRecovery() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        doAnswer(i->{Thread.currentThread().interrupt();throw new IllegalStateException("model interrupted");})
                .when(ai).extract(anyString(),anyString(),anyString(),anyList());
        try {
            service.processPending(queued.id(),user,r->{});
            assertThat(service.find(queued.id()).status()).isEqualTo("PROCESSING");
            assertThat(repository.pending(clock.instant(),10)).isEmpty();
            assertThat(repository.pending(clock.instant().plusSeconds(901),10)).extracting(r->r.id()).contains(queued.id());
            verify(audit,never()).success(eq("PROCUREMENT_MATERIAL_FAILED"),any(),any(),any(),any(),any(),any(),any());
        } finally {Thread.interrupted();}
    }
    @Test void shutdownInterruptionAfterModelResponseDoesNotPublishAResult() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        doAnswer(i->{Thread.currentThread().interrupt();return new MaterialFieldAiPort.Result(List.of(),"model","v1");})
                .when(ai).extract(anyString(),anyString(),anyString(),anyList());
        try {
            service.processPending(queued.id(),user,r->{});
            assertThat(service.find(queued.id()).status()).isEqualTo("PROCESSING");
        } finally {Thread.interrupted();}
    }
    @Test void alreadyInterruptedWorkerDoesNotOpenStoredMaterialOrCallModel() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        try {
            service.processPending(queued.id(),user,r->Thread.currentThread().interrupt());
            assertThat(service.find(queued.id()).status()).isEqualTo("PROCESSING");
            verify(storage,never()).inspect(any());verify(ai,never()).extract(anyString(),anyString(),anyString(),anyList());
        } finally {Thread.interrupted();}
    }
    @Test void parsedResultReopensAndSameOperationDoesNotRepeatAi() {
        String key = UUID.randomUUID().toString(); var first = upload(key); var replay = upload(key);
        assertThat(first.status()).isEqualTo("PARSED");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(service.operation(user, key)).contains(first);
        assertThat(service.find(first.id()).fields().projectName()).isEqualTo("设备更新");
        verify(ai, times(1)).extract(anyString(), anyString(), anyString(), anyList());
        verify(storage, times(2)).discard(any());
    }
    @Test void changedFileUnderSameKeyIsRejected() {
        String key = UUID.randomUUID().toString(); upload(key);
        when(storage.stage(any(), anyLong())).thenReturn(new StagedFile("other", bytes.length, "b".repeat(64), new byte[]{80,75,3,4}));
        assertThatThrownBy(() -> upload(key)).isInstanceOf(BusinessException.class);
    }
    @Test void emptyModelRowsCompleteWithoutInventingFields() {
        doReturn(new MaterialFieldAiPort.Result(List.of(), "model", "v1"))
                .when(ai).extract(anyString(), anyString(), anyString(), anyList());
        var result = upload(UUID.randomUUID().toString());
        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.errorCode()).isNull();
        assertThat(result.fields()).isNotNull();
        assertThat(result.fields().evidence()).containsKey("projectName");
        assertThat(result.fields().projectName()).isEqualTo("设备更新");
        assertThat(service.find(result.id()).status()).isEqualTo("PARSED");
        assertThat(service.retry(result.id(), user).status()).isEqualTo("PARSED");
    }
    @Test void modelRowsWithNoCitableFactsCompleteWithAnEmptyProjection() {
        doReturn(new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one", List.of())), "model", "v1"))
                .when(ai).extract(anyString(), anyString(), anyString(), anyList());
        var result = upload(UUID.randomUUID().toString());
        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.errorCode()).isNull();
        assertThat(result.fields()).isNotNull();
        assertThat(result.fields().evidence()).containsKey("projectName");
    }
    @Test void malformedDocxXmlIsKnownParseFailureAndTheNextQueuedFileStillParses() throws Exception {
        var malformed=new ByteArrayOutputStream();
        try(var source=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes));
            var output=new java.util.zip.ZipOutputStream(malformed)) {
            for(var entry=source.getNextEntry();entry!=null;entry=source.getNextEntry()) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                output.write(entry.getName().equals("word/document.xml")?"<w:document broken".getBytes(java.nio.charset.StandardCharsets.UTF_8):source.readAllBytes());
                output.closeEntry();
            }
        }
        when(guard.validate(any())).thenReturn(malformed.toByteArray(),bytes);
        var first=service.uploadAsync("损坏.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        var second=service.uploadAsync("正常.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        service.processPending(first.id(),user,r->{});
        assertThat(service.find(first.id()).status()).isEqualTo("FAILED");
        assertThat(service.find(first.id()).errorCode()).isEqualTo("DOCUMENT_CONTAINER_INVALID");
        service.processPending(second.id(),user,r->{});
        assertThat(service.find(second.id()).status()).isEqualTo("PARSED");
        verify(ai,times(1)).extract(anyString(),anyString(),anyString(),anyList());
    }
    @Test void promotionUnknownRecoversUsingOriginalRecord() {
        doThrow(new IllegalStateException()).doNothing().when(storage).promote(any(), any());
        var unknown = upload(UUID.randomUUID().toString());
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        var recovered = service.retry(unknown.id(), user);
        assertThat(recovered.id()).isEqualTo(unknown.id()); assertThat(recovered.status()).isEqualTo("PARSED");
        verify(storage, times(1)).promote(any(), any());
    }
    @Test void unknownStorageAndKnownMissingRemainDifferent() {
        when(storage.inspect(any())).thenReturn(StoredFileInspection.unknown());
        var record = upload(UUID.randomUUID().toString()); assertThat(record.status()).isEqualTo("UNKNOWN");
        when(storage.inspect(any())).thenReturn(StoredFileInspection.definitelyMissing());
        var missing = service.retry(record.id(), user);
        assertThat(missing.status()).isEqualTo("FAILED"); assertThat(missing.errorCode()).isEqualTo("SOURCE_FILE_MISSING");
        verifyNoInteractions(ai);
    }
    @Test void openFailureDoesNotClaimMissingAndCapacityIsReleased() {
        when(storage.open(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(upload(UUID.randomUUID().toString()).status()).isEqualTo("UNKNOWN");
        doAnswer(i -> new StoredFileContent(new ByteArrayInputStream(bytes), bytes.length)).when(storage).open(any());
        assertThat(upload(UUID.randomUUID().toString()).status()).isEqualTo("PARSED");
    }
    @Test void concurrentCallFailsFastWithoutEnteringStorageAndReleasesSlot() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); return null; })
                .when(storage).promote(any(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> upload(UUID.randomUUID().toString()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> upload(UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
            verify(storage, times(1)).stage(any(), anyLong());
            release.countDown(); assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo("PARSED");
            assertThat(upload(UUID.randomUUID().toString()).status()).isEqualTo("PARSED");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void expiredLeaseFencesOldWorkerAndOtherUploaderCannotRetry() {
        when(storage.inspect(any())).thenReturn(StoredFileInspection.unknown());
        var original = upload(UUID.randomUUID().toString());
        var claimed = original.claim("new-owner", clock.instant().plusSeconds(900), clock.instant());
        assertThat(repository.claim(claimed, clock.instant())).isTrue();
        assertThat(repository.complete(original.finish("FAILED", null, null, "old", clock.instant()))).isFalse();
        assertThat(repository.find(original.id()).orElseThrow().leaseToken()).isEqualTo("new-owner");
        assertThatThrownBy(() -> service.retry(original.id(), new CurrentUser("other", "other", "其他", Set.of("PROCUREMENT_APPLICANT"))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void queuedUploadsSaveAllFilesBeforeAnyModelCallAndIndependentFailuresNeverStopSiblings() {
        var records = java.util.stream.IntStream.range(0,12).mapToObj(i ->
            service.uploadAsync("材料" + i + ".docx", new ByteArrayInputStream(bytes), UUID.randomUUID().toString(), user)).toList();
        assertThat(records).allMatch(r -> r.status().equals("QUEUED"));
        verifyNoInteractions(ai);
        var normal = new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one", List.of(
                new MaterialFieldAiPort.Fact("projectName", "设备更新", "docx/body/node:1/part:1", "项目名称：设备更新")))), "model", "v1");
        doReturn(new MaterialFieldAiPort.Result(List.of(), "model", "v1"), normal).when(ai)
                .extract(anyString(), anyString(), anyString(), anyList());
        for (var queued : repository.pending(clock.instant(),100)) service.processPending(queued.id(),user,r -> {});
        assertThat(repository.recent(user.userId(),100).stream().filter(r -> r.status().equals("FAILED"))).isEmpty();
        assertThat(repository.recent(user.userId(),100).stream().filter(r -> r.status().equals("PARSED"))).hasSize(12);
        assertThat(repository.pending(clock.instant(),100)).isEmpty();
        for (var record : records) service.processPending(record.id(),user,r -> {});
        verify(ai,times(12)).extract(anyString(),anyString(),anyString(),anyList());
    }
    @Test void modelWorkDoesNotPreventUploadingTheNextFileAndDuplicateScannerCannotRepeatTheCall() throws Exception {
        var queued = service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); release.await(5,TimeUnit.SECONDS); return new MaterialFieldAiPort.Result(List.of(),"model","v1"); })
                .when(ai).extract(anyString(),anyString(),anyString(),anyList());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(() -> service.processPending(queued.id(),user,r -> {}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.uploadAsync("第二份.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user).status()).isEqualTo("QUEUED");
            service.processPending(queued.id(),user,r -> {});
            verify(ai,times(1)).extract(anyString(),anyString(),anyString(),anyList());
            release.countDown(); running.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void expiredWorkIsRediscoveredWithANewLeaseAndUnknownStorageStaysUnknown() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        var expired=queued.claim("interrupted-worker",clock.instant().minusSeconds(1),clock.instant().minusSeconds(1000));
        assertThat(repository.claim(expired,clock.instant())).isTrue();
        assertThat(repository.pending(clock.instant(),10)).extracting(r -> r.id()).contains(queued.id());
        when(storage.inspect(any())).thenReturn(StoredFileInspection.unknown());
        service.processPending(queued.id(),user,r -> assertThat(r.leaseToken()).isNotEqualTo(expired.leaseToken()));
        assertThat(service.find(queued.id()).status()).isEqualTo("UNKNOWN");
        assertThat(repository.complete(expired.finish("FAILED",null,null,"stale",clock.instant()))).isFalse();
        assertThat(repository.pending(clock.instant(),10)).isEmpty();
        verifyNoInteractions(ai);
    }
    @Test void heartbeatDoesNotAllowAStaleWorkerToRenewOrOverwriteAReclaimedLease() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        var owned=queued.claim("worker",clock.instant().plusSeconds(900),clock.instant());
        assertThat(repository.claim(owned,clock.instant())).isTrue();
        assertThat(service.renew(owned)).isTrue();
        assertThat(repository.pending(clock.instant().plusSeconds(899),10)).isEmpty();
        assertThat(repository.renew(owned.id(),"old-worker",clock.instant().plusSeconds(1500))).isFalse();
    }
    @Test void lateScannerCannotImplicitlyRetryAKnownFailedFile() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        var first=queued.claim("first",clock.instant().plusSeconds(900),clock.instant());
        assertThat(repository.claimPending(first,clock.instant())).isTrue();
        assertThat(repository.complete(first.finish("FAILED",null,null,"AI_NO_FIELDS",clock.instant()))).isTrue();
        var staleCandidate=queued.claim("late-scanner",clock.instant().plusSeconds(900),clock.instant());
        assertThat(repository.claimPending(staleCandidate,clock.instant())).isFalse();
        assertThat(service.find(queued.id()).errorCode()).isEqualTo("AI_NO_FIELDS");
    }
    @Test void retryOnlyQueuesTheOriginalDurableFileAndNeverPromotesOrInvokesAiAgainInTheRequest() {
        var queued=service.uploadAsync("材料.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        service.processPending(queued.id(),user,r -> {});
        assertThat(service.retryAsync(queued.id(),user).status()).isEqualTo("PARSED");
        when(storage.inspect(any())).thenReturn(StoredFileInspection.unknown());
        var other=service.uploadAsync("其他.docx",new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),user);
        service.processPending(other.id(),user,r -> {});
        var retried=service.retryAsync(other.id(),user);
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.id()).isEqualTo(other.id()); assertThat(retried.operationKey()).isEqualTo(other.operationKey());
        assertThat(service.retryAsync(other.id(),user).id()).isEqualTo(other.id());
        verify(storage,times(2)).promote(any(),any());
    }
}
