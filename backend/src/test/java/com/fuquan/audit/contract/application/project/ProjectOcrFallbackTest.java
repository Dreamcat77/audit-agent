package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.test.ProjectFileFixtures;
import com.fuquan.audit.integration.file.FileStorageProperties;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.OcrPage;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectOcrFallbackTest {

    @TempDir Path storageRoot;

    @Test void singlePageFinishingAfterDocumentDeadlineCannotPublishImmutableParse() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicLong();
        OcrPort ocr=request->{time.set(Duration.ofMinutes(31).toNanos());
            return new OcrResult(request.documentId(),"LATE OCR",0.95,
                    List.of(new OcrPage(1,"LATE OCR",0.95)),false,"PADDLEOCR_3.7.0",10L);};
        var support=new Support(storageRoot.resolve("late-single"),ocr,time::get);
        var file=support.upload("late.pdf","application/pdf",pdf(null),"late-single");
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(file.safeReasonCode()).isEqualTo("PDF_PROCESSING_DEADLINE");
        assertThat(support.parseResults.findLatestByFileId(file.id())).isEmpty();
    }

    @Test void finalScanPageFinishingAfterDeadlineCannotPublishPartialDocument() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicLong();
        OcrPort ocr=request->{time.set(Duration.ofMinutes(31).toNanos());
            return new OcrResult(request.documentId(),"LATE OCR",0.95,
                    List.of(new OcrPage(1,"LATE OCR",0.95)),false,"PADDLEOCR_3.7.0",10L);};
        var support=new Support(storageRoot.resolve("late-final"),ocr,time::get);
        byte[] bytes;
        try(var document=new PDDocument();var scan=PDDocument.load(pdf(null))) {
            document.addPage(new PDPage());
            document.importPage(scan.getPage(0));
            var out=new ByteArrayOutputStream();document.save(out);bytes=out.toByteArray();
        }
        var file=support.upload("late.pdf","application/pdf",bytes,"late-final");
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(file.safeReasonCode()).isEqualTo("PDF_PROCESSING_DEADLINE");
        assertThat(support.parseResults.findLatestByFileId(file.id())).isEmpty();
    }

    @Test void provenSlowPageWithinThirtyMinuteBudgetCanContinueRemainingPages() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicLong();
        OcrPort ocr=request->{time.set(Duration.ofMinutes(16).toNanos());
            return new OcrResult(request.documentId(),"SLOW OCR",0.95,
                    List.of(new OcrPage(1,"SLOW OCR",0.95)),false,"PADDLEOCR_3.7.0",10L);};
        var support=new Support(storageRoot.resolve("within-budget"),ocr,time::get);
        byte[] bytes;
        try(var document=PDDocument.load(pdf(null))) {
            document.addPage(new PDPage());
            var out=new ByteArrayOutputStream();document.save(out);bytes=out.toByteArray();
        }
        var file=support.upload("slow.pdf","application/pdf",bytes,"within-budget");
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(file.parsedText()).isEqualTo("SLOW OCR\n");
        assertThat(support.parseResults.findLatestByFileId(file.id())).isPresent();
    }

    @Test void mixedPdfOnlyRecognizesScanPagesAndKeepsNativeBlankAndScanPageOrder() throws Exception {
        List<OcrRequest> requests=new ArrayList<>();
        OcrPort ocr=request->{requests.add(request);return new OcrResult(request.documentId(),"SCAN SECOND",0.95,
                List.of(new OcrPage(1,"SCAN SECOND",0.95)),false,"PADDLEOCR_3.7.0",10L);};
        var support=new Support(storageRoot.resolve("mixed"),ocr);
        byte[] bytes;
        try(var document=new PDDocument()) {
            for(int i=1;i<=4;i++) {
                var page=new PDPage();document.addPage(page);
                if(i==3)continue;
                try(var content=new PDPageContentStream(document,page)) {
                    if(i==2){content.addRect(40,650,30,30);content.fill();}
                    else{content.beginText();content.setFont(PDType1Font.HELVETICA,12);content.newLineAtOffset(40,700);
                        content.showText(i==1?"NATIVE FIRST CONTRACT":"NATIVE FOURTH ATTACHMENT");content.endText();}
                }
            }
            var out=new ByteArrayOutputStream();document.save(out);bytes=out.toByteArray();
        }
        var file=support.upload("mixed.pdf","application/pdf",bytes,"mixed");
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(requests).hasSize(1);
        try(var recognized=PDDocument.load(requests.get(0).source().openStream())) {
            assertThat(recognized.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(file.parsedText()).isEqualTo("NATIVE FIRST CONTRACT\nSCAN SECOND\n\nNATIVE FOURTH ATTACHMENT");
        assertThat(file.parseProvenance().pageCount()).isEqualTo(4);
        assertThat(support.parseResults.findLatestByFileId(file.id()).orElseThrow().canonicalText()).isEqualTo(file.parsedText());
    }

    @Test void certainlyBlankPdfDoesNotCallOcrOrInventSuccessfulText() throws Exception {
        var ocr=new RecordingOcrPort();var support=new Support(storageRoot.resolve("blank"),ocr);
        byte[] bytes;
        try(var document=new PDDocument()) {
            document.addPage(new PDPage());var out=new ByteArrayOutputStream();document.save(out);bytes=out.toByteArray();
        }
        var file=support.upload("blank.pdf","application/pdf",bytes,"blank");
        assertThat(ocr.requests).isEmpty();
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(file.safeReasonCode()).isEqualTo("PARSE_EMPTY_RESULT");
        assertThat(support.parseResults.findLatestByFileId(file.id())).isEmpty();
    }

    @Test
    void usefulTextPdfUsesPdfBoxWithoutCallingOcr() throws Exception {
        RecordingOcrPort ocr = new RecordingOcrPort();
        Support support = new Support(storageRoot.resolve("text-pdf"), ocr);

        ProjectFile file = support.upload("contract.pdf", "application/pdf",
                pdf("Contract No C-001 Contract Amount 42.50"), "text-pdf");

        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(file.parsedText()).contains("Contract No C-001");
        assertThat(file.parseProvenance().method()).isEqualTo(ProjectFileParseMethod.PDF_TEXT);
        assertThat(file.parseProvenance().engine()).isEqualTo("PDFBOX_2.0.30");
        assertThat(file.parseProvenance().pageCount()).isEqualTo(1);
        assertThat(ocr.requests).isEmpty();
    }

    @Test
    void blankPdfFallsBackToOneOcrCallOverThePersistedStorageKey() throws Exception {
        RecordingOcrPort ocr = new RecordingOcrPort();
        Support support = new Support(storageRoot.resolve("blank-pdf"), ocr);

        ProjectFile file = support.upload("scanned.pdf", "application/pdf", pdf(null), "blank-pdf");

        assertThat(ocr.requests).singleElement().satisfies(request -> {
            assertThat(request.documentId()).isEqualTo(file.id().toString());
            assertThat(request.source().contentLength()).isEqualTo(file.sizeBytes());
        });
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(file.parsedText()).isEqualTo("OCR text for scanned.pdf");
        assertThat(file.parseProvenance().method()).isEqualTo(ProjectFileParseMethod.OCR);
        assertThat(file.parseProvenance().engine()).isEqualTo("PADDLEOCR_3.7.0");
        assertThat(file.parseProvenance().confidence()).isEqualByComparingTo("0.9100");
        assertThat(support.parseResults.findLatestByFileId(file.id())).hasValueSatisfying(result -> {
            assertThat(result.canonicalText()).isEqualTo("OCR text for scanned.pdf");
            assertThat(result.fileSha256()).isEqualTo(file.sha256());
            assertThat(result.parseVersion()).isEqualTo(1);
        });
        verify(support.progression).ensureRoleAssessments(file.projectId(),
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), support.actor);
    }

    @Test
    void pngJpgAndJpegEachUseOcr() {
        RecordingOcrPort ocr = new RecordingOcrPort();
        Support support = new Support(storageRoot.resolve("images"), ocr);

        ProjectFile png = support.uploadFixture("photo.png", "image/png", "valid.png", "png");
        ProjectFile jpg = support.uploadFixture("photo.jpg", "image/jpeg", "valid.jpg", "jpg");
        ProjectFile jpeg = support.uploadFixture("photo.jpeg", "image/jpeg", "valid.jpeg", "jpeg");

        assertThat(ocr.requests).extracting(OcrRequest::fileName)
                .containsExactly("photo.png", "photo.jpg", "photo.jpeg");
        assertThat(List.of(png, jpg, jpeg)).allSatisfy(file -> {
            assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
            assertThat(file.parseProvenance().method()).isEqualTo(ProjectFileParseMethod.OCR);
        });
    }

    @Test
    void simulatedLocalOcrCannotBecomeAParsedDemoResult() throws Exception {
        OcrPort simulated = request -> new OcrResult(request.documentId(), "fake text", 0.99,
                List.of(new OcrPage(1, "fake text", 0.99)), true, "FAKE_OCR", 0L);
        Support support = new Support(storageRoot.resolve("simulated"), simulated);

        ProjectFile file = support.upload("scanned.pdf", "application/pdf", pdf(null), "simulated");

        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(file.safeReasonCode()).isEqualTo("OCR_NOT_AVAILABLE");
        assertThat(file.parsedText()).isNull();
        assertThat(file.parseProvenance().present()).isFalse();
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(40, 700);
                    content.showText(text);
                    content.endText();
                }
            } else {
                // A scan-like graphic page, not a genuinely contentless blank page.
                try(var content=new PDPageContentStream(document,page)) {
                    content.addRect(40,650,30,30);content.fill();
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static final class RecordingOcrPort implements OcrPort {
        private final List<OcrRequest> requests = new ArrayList<>();

        @Override
        public OcrResult recognize(OcrRequest request) {
            requests.add(request);
            String text = "OCR text for " + request.fileName();
            return new OcrResult(request.documentId(), text, 0.91,
                    List.of(new OcrPage(1, text, 0.91)), false, "PADDLEOCR_3.7.0", 120L);
        }
    }

    private static final class Support {
        private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        private final Project project;
        private final CurrentUser actor = new CurrentUser("ocr-test-user", "ocr.test", "OCR Test", Set.of("AUDITOR"));
        private final ProjectFileIngestionService ingestion;
        private final InMemoryProjectFileParseResultRepository parseResults =
                new InMemoryProjectFileParseResultRepository();
        private final ProjectIntakeProgressionService progression =
                mock(ProjectIntakeProgressionService.class);

        private Support(Path root, OcrPort ocr) {
            this(root,ocr,System::nanoTime);
        }

        private Support(Path root, OcrPort ocr,java.util.function.LongSupplier monotonicNanos) {
            InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
            ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                    repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                    repositories.extractionResultRepository(), clock);
            project = projects.createBlank("ocr-test-user");
            FileStorageProperties storageProperties = new FileStorageProperties();
            storageProperties.setRoot(root.toString());
            FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
            ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
            ProjectFilePolicy policy = new ProjectFilePolicy(limits, Duration.ofMinutes(15));
            SelectedProjectArchiveReader archiveReader = new SelectedProjectArchiveReader(limits);
            ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                    repositories.projectRepository(), repositories.projectFileRepository(),
                    repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                    parseResults, new InMemoryIdempotencyAdapter(),
                    event -> { }, clock,
                    new FlowDebugLogger(new ObservabilityProperties()));
            ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(archiveReader,
                    new ProjectFileClassifier(), policy, new ProjectArchiveStorageKeyFactory(), storage);
            ingestion = new ProjectFileIngestionService(projects, storage, policy, new ProjectFileClassifier(),
                    List.of(new ProjectPdfTextParser()), ocr,
                    new ProjectFactExtractor(new ExtractionKeyFactory(), clock),
                    new ContractCandidateAssembler(clock), archives, coordinator,
                    progression, () -> Optional.of(actor), clock,monotonicNanos);
        }

        private ProjectFile uploadFixture(String filename, String mediaType, String fixture, String key) {
            return upload(filename, mediaType, ProjectFileFixtures.bytes(fixture), key);
        }

        private ProjectFile upload(String filename, String mediaType, byte[] bytes, String key) {
            return ingestion.upload(new ProjectFileUploadCommand(project.id(), filename, mediaType, bytes.length,
                    new ByteArrayInputStream(bytes), key,
                    new ProjectRequestMetadata("request", "trace", "127.0.0.1")));
        }
    }
}
