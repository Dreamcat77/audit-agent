package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import com.fuquan.audit.review.application.port.OcrPage;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcurementTenderDocumentServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final ProcurementProjectQueryPort projects = new ProcurementProjectQueryPort() {
        @Override
        public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(java.util.List.of(), 0);
        }

        @Override
        public Optional<ProcurementProject> findById(UUID id) {
            return id.equals(projectId) ? Optional.of(new ProcurementProject(
                    projectId, "PR-1", "招标文件测试项目", "M1001", "办公设备", null,
                    null, "DRAFT", Instant.parse("2026-09-22T00:00:00Z"))) : Optional.empty();
        }
    };
    private final ProcurementTenderDocumentRepository repository = mock(ProcurementTenderDocumentRepository.class);
    private final ProjectDocxTextParser docxParser = mock(ProjectDocxTextParser.class);
    private final ProjectPdfTextParser pdfParser = mock(ProjectPdfTextParser.class);
    private final OcrPort ocr = mock(OcrPort.class);
    private FakeStorage storage;
    private ProcurementTenderDocumentService service;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        service = new ProcurementTenderDocumentService(projects, repository, storage, docxParser, pdfParser, ocr,
                ProjectIntakeProperties.defaults(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-22T01:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void docxIsParsedAndParagraphLocatorIsReturned() throws Exception {
        when(docxParser.parse(any(), any())).thenReturn(new ParsedProjectDocument(
                java.util.List.of(new ParsedProjectDocument.Segment("docx/body/paragraph:18", "资格要求", null, 18))));

        var result = service.upload(projectId, "招标文件.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new ByteArrayInputStream(bytes("PK\003\004docx")), false, "user-1");

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.parseMethod()).isEqualTo("DOCX_TEXT");
        assertThat(result.parseResult().segments()).singleElement()
                .extracting(segment -> segment.sourceLocator()).isEqualTo("docx/body/paragraph:18");
    }

    @Test
    void textPdfIsParsedWithPageLocatorWithoutCallingOcr() throws Exception {
        when(pdfParser.parse(any(), any())).thenReturn(new ParsedProjectDocument(
                java.util.List.of(new ParsedProjectDocument.Segment("pdf/page:3",
                        "投标文件资格审查要求", 3, null))));

        var result = service.upload(projectId, "招标文件.pdf", "application/pdf",
                new ByteArrayInputStream(bytes("%PDF-1.7")), false, "user-1");

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.parseMethod()).isEqualTo("PDF_TEXT");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.parseResult().segments()).singleElement()
                .extracting(segment -> segment.sourceLocator()).isEqualTo("pdf/page:3");
    }

    @Test
    void blankPdfFallsBackToOcrAndKeepsPageConfidence() {
        when(pdfParser.parse(any(), any())).thenReturn(new ParsedProjectDocument(
                java.util.List.of(new ParsedProjectDocument.Segment("pdf/page:1", "", 1, null))));
        when(ocr.recognize(any())).thenReturn(new OcrResult("document", "扫描文本", 0.88,
                java.util.List.of(new OcrPage(1, "扫描文本", 0.91)), false, "PADDLEOCR", 100));

        var result = service.upload(projectId, "扫描件.pdf", "application/pdf",
                new ByteArrayInputStream(bytes("%PDF-1.7")), false, "user-1");

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.parseMethod()).isEqualTo("OCR");
        assertThat(result.parseConfidence()).isEqualTo(0.88);
        assertThat(result.parseResult().segments()).singleElement().satisfies(segment -> {
            assertThat(segment.sourceLocator()).isEqualTo("ocr/page:1");
            assertThat(segment.confidence()).isEqualTo(0.91);
        });
    }

    @Test
    void simulatedOrUnavailableOcrCreatesExplicitFailedRecord() {
        when(pdfParser.parse(any(), any())).thenReturn(new ParsedProjectDocument(
                java.util.List.of(new ParsedProjectDocument.Segment("pdf/page:1", "", 1, null))));
        when(ocr.recognize(any())).thenReturn(new OcrResult("document", "", 0.0,
                java.util.List.of(), true, "FAKE_OCR", 0));

        var result = service.upload(projectId, "扫描件.pdf", "application/pdf",
                new ByteArrayInputStream(bytes("%PDF-1.7")), false, "user-1");

        assertThat(result.status()).isEqualTo("PARSE_FAILED");
        assertThat(result.parseError()).isEqualTo("OCR_NOT_AVAILABLE");
    }

    @Test
    void unsupportedExtensionIsRejectedBeforeARecordIsCreated() {
        assertThatThrownBy(() -> service.upload(projectId, "招标文件.txt", "text/plain",
                new ByteArrayInputStream(bytes("plain text")), false, "user-1"))
                .hasMessageContaining("仅支持 .docx 或 .pdf");
    }

    @Test
    void latestQueryReturnsPersistedDocument() {
        UUID documentId = UUID.randomUUID();
        ProcurementTenderDocument document = new ProcurementTenderDocument(documentId, projectId, "文件.pdf", "pdf",
                "application/pdf", 10, "a".repeat(64), "b".repeat(64),
                com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus.PARSE_FAILED, null, null,
                null, null, null, "PDF_PARSE_FAILED", "user-1", Instant.now(), Instant.now());
        when(repository.findLatestByProjectId(projectId)).thenReturn(Optional.of(document));

        assertThat(service.findLatest(projectId)).isPresent().get().extracting(value -> value.status())
                .isEqualTo("PARSE_FAILED");
    }

    @Test
    void sharedReviewEntryAcceptsScanAndOfficeTextAndKeepsEveryReaderSegment() throws Exception {
        var reader=mock(com.fuquan.audit.common.material.application.port.DocumentTextPort.class);
        service.setSharedReader(reader);
        var transactions=mock(ProcurementMaterialTransactions.class);
        when(transactions.register(any())).thenAnswer(call->call.getArgument(0));
        for(var name:java.util.List.of("材料.txt","扫描.png","旧版.doc","混合.pdf")) {
            byte[] content=name.endsWith(".png")?new byte[]{(byte)137,80,78,71,13,10,26,10}:
                    name.endsWith(".doc")?new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1}:
                    bytes(name.endsWith(".pdf")?"%PDF-1.7":"材料正文");
            String hash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            when(reader.read(any(),org.mockito.ArgumentMatchers.eq(name),any(),org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(java.util.List.of(
                    new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("page:1/text","首段正文"),
                    new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("page:2/ocr","扫描页末尾金额壹拾万元")),
                    name.endsWith(".txt")||name.endsWith(".doc")?"OFFICE_TEXT":"OFFICE_TEXT_OCR",hash));
            var result=service.uploadMaterial(projectId,name,null,new ByteArrayInputStream(content),"actor",transactions);
            assertThat(result.status()).isEqualTo("AVAILABLE");
            assertThat(result.parseResult().segments()).hasSize(2);
            assertThat(result.parseResult().fullText()).contains("首段正文","扫描页末尾金额壹拾万元");
            if(name.endsWith(".png")||name.endsWith(".pdf"))assertThat(result.parseMethod()).isEqualTo("OCR");
            else assertThat(result.parseMethod()).isNull();
        }
        org.mockito.Mockito.verifyNoInteractions(docxParser,pdfParser,ocr);
    }

    @Test
    void sharedReaderFailureAndSourceHashMismatchNeverPublishAvailableMaterial() throws Exception {
        var reader=mock(com.fuquan.audit.common.material.application.port.DocumentTextPort.class);
        service.setSharedReader(reader);
        var transactions=mock(ProcurementMaterialTransactions.class);
        when(transactions.register(any())).thenAnswer(call->call.getArgument(0));
        when(reader.read(any(),any(),any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenThrow(
                new com.fuquan.audit.common.material.application.port.DocumentTextPort.ReadFailure("OCR_PAGE_COVERAGE_INVALID"));
        var failed=service.uploadMaterial(projectId,"损坏.txt",null,new ByteArrayInputStream(bytes("text")),"actor",transactions);
        assertThat(failed.status()).isEqualTo("PARSE_FAILED");
        assertThat(failed.parseError()).isEqualTo("OCR_PAGE_COVERAGE_INVALID");
        org.mockito.Mockito.reset(reader);
        when(reader.read(any(),any(),any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(java.util.List.of(),"OFFICE_TEXT","wrong"));
        var mismatch=service.uploadMaterial(projectId,"篡改.txt",null,new ByteArrayInputStream(bytes("text")),"actor",transactions);
        assertThat(mismatch.status()).isEqualTo("PARSE_FAILED");
        assertThat(mismatch.parseError()).isEqualTo("SOURCE_HASH_MISMATCH");
    }

    private com.fuquan.audit.common.material.domain.MaterialIntake intake(String owner, String state, long size, String sha) {
        return new com.fuquan.audit.common.material.domain.MaterialIntake(UUID.randomUUID(), owner, "operation",
            "材料.txt", "text/plain", size, sha, "b".repeat(64), state, null, Instant.EPOCH, null, null, null,
            Instant.EPOCH, Instant.EPOCH);
    }

    @Test void intakeReusesVerifiedBytesAndSuccessfulParseWithoutAnotherUploadOrCopy() throws Exception {
        var reader = mock(com.fuquan.audit.common.material.application.port.DocumentTextPort.class);
        service.setSharedReader(reader);
        byte[] content = bytes("采购需求：测试办公设备，预算壹拾万元整");
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        storage.objects.put("b".repeat(64), content);
        var record = intake("actor", "PARSED", content.length, hash);
        var transactions = mock(ProcurementMaterialTransactions.class);
        when(transactions.register(any())).thenAnswer(call -> call.getArgument(0));
        when(reader.read(any(),any(),any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(
                java.util.List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("text:1",new String(content,StandardCharsets.UTF_8))), "OFFICE_TEXT",hash));
        var first = service.reuseIntake(projectId,record,"actor",transactions);
        assertThat(first.status()).isEqualTo("AVAILABLE");
        var capture = org.mockito.ArgumentCaptor.forClass(ProcurementTenderDocument.class);
        org.mockito.Mockito.verify(repository).update(capture.capture());
        var persisted = capture.getValue();
        assertThat(persisted.storageKey()).isEqualTo("b".repeat(64));
        when(transactions.register(any())).thenReturn(persisted);
        assertThat(service.reuseIntake(projectId,record,"actor",transactions).id()).isEqualTo(first.id());
        org.mockito.Mockito.verify(reader,org.mockito.Mockito.times(1)).read(any(),any(),any(),org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt());
        assertThat(storage.staged).isEmpty(); assertThat(storage.objects).hasSize(1);
    }

    @Test void intakeStorageUnknownMissingTamperedAndWrongOwnerFailBeforeAnyReviewWrite() {
        var transactions = mock(ProcurementMaterialTransactions.class);
        var record = intake("actor","PARSED",3,"expected");
        for (var state : java.util.List.of(StoredFileInspection.definitelyMissing(), StoredFileInspection.unknown(),
                StoredFileInspection.present(new com.fuquan.audit.contract.application.port.StoredFileInfo(3,"changed")),
                StoredFileInspection.present(new com.fuquan.audit.contract.application.port.StoredFileInfo(4,"expected")))) {
            storage.inspectionOverride = state;
            assertThatThrownBy(() -> service.reuseIntake(projectId,record,"actor",transactions))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        }
        assertThatThrownBy(() -> service.reuseIntake(projectId,record,"other",transactions))
            .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThatThrownBy(() -> service.reuseIntake(projectId,intake("actor","FAILED",3,"expected"),"actor",transactions))
            .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(transactions,repository,docxParser,pdfParser,ocr);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeStorage implements FileStoragePort {
        private StoredFileInspection inspectionOverride;
        private final Map<String, byte[]> staged = new ConcurrentHashMap<>();
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public StagedFile stage(InputStream inputStream, long sizeLimit) {
            try {
                byte[] value = inputStream.readAllBytes();
                String sha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
                String id = UUID.randomUUID() + ".part";
                staged.put(id, value);
                return new StagedFile(id, value.length, sha, Arrays.copyOf(value, Math.min(16, value.length)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void promote(StagedFile stagedFile, StorageKey storageKey) {
            objects.put(storageKey.value(), staged.remove(stagedFile.stagingId()));
        }

        @Override
        public StoredFileContent open(StorageKey storageKey) {
            byte[] value = objects.get(storageKey.value());
            if (value == null) throw new IllegalStateException("missing object");
            return new StoredFileContent(new ByteArrayInputStream(value), value.length);
        }

        @Override public void discard(StagedFile stagedFile) { staged.remove(stagedFile.stagingId()); }
        @Override public StoredFileInspection inspect(StorageKey storageKey) {
            if (inspectionOverride != null) return inspectionOverride;
            byte[] value = objects.get(storageKey.value());
            if (value == null) return StoredFileInspection.definitelyMissing();
            try { return StoredFileInspection.present(new com.fuquan.audit.contract.application.port.StoredFileInfo(value.length,
                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)))); }
            catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        }
    }
}
