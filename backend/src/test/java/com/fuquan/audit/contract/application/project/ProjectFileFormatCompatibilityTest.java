package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ProjectDocumentParser;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.test.ProjectFileFixtures;
import com.fuquan.audit.integration.file.FileStorageProperties;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.OcrPage;
import com.fuquan.audit.review.application.port.OcrPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileFormatCompatibilityTest {
    @TempDir Path storageRoot;

    @Test
    void directUploadMatrixAllowsZipPdfDocxAndOcrImages() throws Exception {
        Support support = new Support(storageRoot.resolve("direct-allowed"));

        ProjectFile pdf = support.upload("direct.pdf", "application/pdf", "valid.pdf", "direct-pdf");
        ProjectFile docx = support.upload("direct.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "valid.docx", "direct-docx");
        assertThat(support.ocrRequests).isEmpty();
        ProjectFile png = support.upload("direct.png", "image/png", "valid.png", "direct-png");
        ProjectFile jpg = support.upload("direct.jpg", "image/jpeg", "valid.jpg", "direct-jpg");
        ProjectFile jpeg = support.upload("direct.jpeg", "image/jpeg", "valid.jpeg", "direct-jpeg");
        assertThat(support.ocrRequests).containsExactly("direct.png", "direct.jpg", "direct.jpeg");
        ProjectFile zip = support.upload("direct.zip", "application/zip",
                "project_file_format_matrix.zip", "direct-zip");

        assertThat(pdf.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(pdf.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(docx.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(docx.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(zip.category()).isEqualTo(ProjectFileCategory.PACKAGE);
        assertThat(zip.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(zip.parseStatus()).isEqualTo(ProjectFileParseStatus.NOT_APPLICABLE);
        assertThat(List.of(png, jpg, jpeg)).allSatisfy(file ->
                assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED));
    }

    @Test
    void directRejectionsOccurBeforeDurableUploadStart() throws Exception {
        Support support = new Support(storageRoot.resolve("direct-rejected"));
        List<UploadCase> rejected = List.of(
                new UploadCase("wrong-mime.pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "valid.pdf"),
                new UploadCase("mz.pdf", "application/pdf", "invalid-pdf-renamed.pdf"),
                new UploadCase("plain.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "invalid-docx-renamed.docx"),
                new UploadCase("zip.pdf", "application/pdf", "nested.zip"),
                new UploadCase("renamed.doc", "application/msword", "valid.docx"),
                new UploadCase("legacy.xls", "application/vnd.ms-excel", "valid.xls"),
                new UploadCase("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "valid.xlsx"),
                new UploadCase("notes.txt", "text/plain", "utf8-ascii.txt"));

        for (UploadCase upload : rejected) {
            int fileCount = support.files().size();
            int idempotencyCount = support.idempotency.size();
            int auditCount = support.audits.size();

            assertThatThrownBy(() -> support.upload(upload.filename(), upload.mediaType(), upload.fixture(),
                    "reject-" + upload.filename()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.INVALID_ARGUMENT));

            assertThat(support.files()).hasSize(fileCount);
            assertThat(support.idempotency.size()).isEqualTo(idempotencyCount);
            assertThat(support.audits).hasSize(auditCount);
        }
    }

    @Test
    void malformedLegacyWordRetainsOriginalButCannotPublishParsedText() throws Exception {
        Support support=new Support(storageRoot.resolve("malformed-word"));
        ProjectFile file=support.upload("legacy.doc","application/msword","valid.doc","malformed-word");
        assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(file.safeReasonCode()).startsWith("DOC_");
        assertThat(file.parsedText()).isNull();
        assertContent(support,file,"valid.doc");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="audit.word97.fixture",matches=".+")
    void genuineLegacyWordIsAdmittedAndParsedThroughTheActualPipeline() throws Exception {
        Support support=new Support(storageRoot.resolve("genuine-word"));
        byte[] bytes=java.nio.file.Files.readAllBytes(Path.of(System.getProperty("audit.word97.fixture"),"真实Word97_软件采购合同.doc"));
        ProjectFile file=support.uploadBytes("genuine.doc","application/msword",bytes,"genuine-word");
        assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(file.parseProvenance().method()).isEqualTo(com.fuquan.audit.contract.domain.project.ProjectFileParseMethod.DOC_TEXT);
        assertThat(file.parsedText().length()).isBetween(13000,14500);
        try(var input=support.content.openVerified(file).inputStream()) { assertThat(java.util.Arrays.equals(input.readAllBytes(),bytes)).isTrue(); }
    }

    @Test
    void realFormatMatrixIngestsFirstLevelChildrenWithStableParseAndSourceSemantics() throws Exception {
        Support support = new Support(storageRoot.resolve("archive-matrix"));

        ProjectFile root = support.upload("project_file_format_matrix.zip", "application/zip",
                "project_file_format_matrix.zip", "format-matrix");
        Map<String, ProjectFile> children = new LinkedHashMap<>();
        support.files().stream().filter(file -> file.parentArchiveFileId() != null)
                .forEach(file -> children.put(file.normalizedRelativePath(), file));

        assertThat(root.category()).isEqualTo(ProjectFileCategory.PACKAGE);
        assertThat(root.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(root.parseStatus()).isEqualTo(ProjectFileParseStatus.NOT_APPLICABLE);
        assertThat(children).hasSize(10).containsOnlyKeys(
                "01_合同/合同正文.docx", "02_招标文件/招标文件.pdf", "03_投标文件/附件.doc",
                "04_其他材料/报价表.xls", "04_其他材料/明细表.xlsx", "04_其他材料/现场照片.png",
                "04_其他材料/现场照片.jpg", "04_其他材料/现场照片.jpeg", "04_其他材料/说明.txt",
                "04_其他材料/nested.zip");
        assertThat(children.values()).allSatisfy(file ->
                assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE));

        ProjectFile docx = children.get("01_合同/合同正文.docx");
        ProjectFile pdf = children.get("02_招标文件/招标文件.pdf");
        assertThat(docx.category()).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(docx.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(support.parsedText.read(docx)).contains("Contract No: FORMAT-001");
        assertThat(pdf.category()).isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(pdf.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(support.parsedText.read(pdf)).contains("Tender document compatibility fixture");
        assertThat(children.get("03_投标文件/附件.doc").category())
                .isEqualTo(ProjectFileCategory.BID_RESPONSE);

        List<ProjectFile> ocrParsed = List.of(children.get("04_其他材料/现场照片.png"),
                children.get("04_其他材料/现场照片.jpg"), children.get("04_其他材料/现场照片.jpeg"));
        assertThat(ocrParsed).allSatisfy(file -> {
            assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
            assertThat(file.parsedText()).contains("OCR text for");
            assertThat(support.parsedText.read(file)).isEqualTo(file.parsedText());
        });
        assertThat(support.ocrRequests).containsExactly("现场照片.png", "现场照片.jpg", "现场照片.jpeg");
        List<ProjectFile> unsupported = children.values().stream()
                .filter(file -> file.parseStatus() == ProjectFileParseStatus.UNSUPPORTED).toList();
        assertThat(unsupported).hasSize(4).allSatisfy(file -> {
            assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.UNSUPPORTED);
            assertThat(file.safeReasonCode()).isEqualTo("PARSER_UNSUPPORTED");
            assertThat(file.parsedText()).isNull();
            assertThat(support.parsedText.read(file)).isNull();
        });

        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .isEmpty();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .isNotEmpty().allSatisfy(fact -> assertThat(fact.sourceFileId()).isEqualTo(docx.id()));
        assertThat(support.files()).noneMatch(file -> file.normalizedRelativePath() != null
                && file.normalizedRelativePath().contains("second-level"));

        assertContent(support, children.get("04_其他材料/现场照片.png"), "valid.png");
        assertContent(support, children.get("04_其他材料/现场照片.jpg"), "valid.jpg");
        assertContent(support, children.get("04_其他材料/现场照片.jpeg"), "valid.jpeg");
        ProjectApplicationService.ProjectDetail detail = support.projects.detail(support.project.id());
        assertThat(children.get("03_投标文件/附件.doc").parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(children.get("03_投标文件/附件.doc").safeReasonCode()).startsWith("DOC_");
        assertThat(detail.failedFileCount()).isEqualTo(1);
        assertThat(detail.unsupportedFileCount()).isEqualTo(4);
    }

    @Test
    void pathClassificationIsOnlyAHintAndNeverCreatesCurrentPipelineCandidate() throws Exception {
        Support support = new Support(storageRoot.resolve("classifier-candidate"));
        byte[] parseableContract = ProjectFileFixtures.bytes("valid.docx");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("01_合同/智慧办公终端采购合同.docx", parseableContract);
        entries.put("03_招标文件/政府采购合同模板.docx", parseableContract);

        support.uploadBytes("classifier-candidate.zip", "application/zip", zip(entries),
                "classifier-candidate");

        Map<String, ProjectFile> children = new LinkedHashMap<>();
        support.files().stream().filter(file -> file.parentArchiveFileId() != null)
                .forEach(file -> children.put(file.normalizedRelativePath(), file));
        ProjectFile contract = children.get("01_合同/智慧办公终端采购合同.docx");
        ProjectFile tenderTemplate = children.get("03_招标文件/政府采购合同模板.docx");

        assertThat(contract.category()).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(contract.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(tenderTemplate.category()).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(tenderTemplate.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .anySatisfy(fact -> assertThat(fact.sourceFileId()).isEqualTo(contract.id()));
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .isEmpty();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws java.io.IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream archive = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
            archive.finish();
            return output.toByteArray();
        }
    }

    private static void assertContent(Support support, ProjectFile file, String fixture) throws Exception {
        StoredFileContent content = support.content.openVerified(file);
        try (java.io.InputStream input = content.inputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(ProjectFileFixtures.bytes(fixture));
        }
    }

    private record UploadCase(String filename, String mediaType, String fixture) {
    }

    private static final class Support {
        private static final ProjectRequestMetadata META =
                new ProjectRequestMetadata("format-request", "format-trace", "127.0.0.1");
        private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        private final InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        private final List<OperationAuditEvent> audits = new ArrayList<>();
        private final List<String> ocrRequests = new ArrayList<>();
        private final FileSystemStorageAdapter storage;
        private final ProjectApplicationService projects;
        private final ProjectFileIngestionService ingestion;
        private final ProjectParsedTextReadService parsedText;
        private final ProjectFileContentReadService content;
        private final Project project;

        private Support(Path storageRoot) {
            FileStorageProperties storageProperties = new FileStorageProperties();
            storageProperties.setRoot(storageRoot.toString());
            storage = new FileSystemStorageAdapter(storageProperties);
            ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
            ProjectFilePolicy policy = new ProjectFilePolicy(limits, Duration.ofMinutes(15));
            SelectedProjectArchiveReader archiveReader = new SelectedProjectArchiveReader(limits);
            List<ProjectDocumentParser> parsers = List.of(new ProjectPdfTextParser(),
                    new ProjectDocxTextParser(new ProjectDocxContainerGuard(archiveReader, limits)),
                    new com.fuquan.audit.contract.application.project.parse.ProjectDocTextParser(limits));
            projects = new ProjectApplicationService(repositories.projectRepository(),
                    repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                    repositories.extractionResultRepository(), clock);
            ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                    repositories.projectRepository(), repositories.projectFileRepository(),
                    repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                    new InMemoryProjectFileParseResultRepository(), idempotency, audits::add, clock,
                    new FlowDebugLogger(new ObservabilityProperties()));
            ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(archiveReader,
                    new ProjectFileClassifier(), policy, new ProjectArchiveStorageKeyFactory(), storage);
            CurrentUser actor = new CurrentUser("format-user", "format.user", "Format User", Set.of("AUDITOR"));
            OcrPort ocr = request -> {
                ocrRequests.add(request.fileName());
                String text = "OCR text for " + request.fileName();
                return new OcrResult(request.documentId(), text, 0.90,
                        List.of(new OcrPage(1, text, 0.90)), false, "PADDLEOCR_TEST", 5L);
            };
            ingestion = new ProjectFileIngestionService(projects, storage, policy, new ProjectFileClassifier(),
                    parsers, ocr, new ProjectFactExtractor(new ExtractionKeyFactory(), clock),
                    new ContractCandidateAssembler(clock), archives, coordinator,
                    () -> Optional.of(actor), clock);
            parsedText = new ProjectParsedTextReadService(storage);
            content = new ProjectFileContentReadService(storage);
            project = projects.createBlank(actor.userId());
        }

        private ProjectFile upload(String filename, String mediaType, String fixture, String key) {
            byte[] bytes = ProjectFileFixtures.bytes(fixture);
            return uploadBytes(filename, mediaType, bytes, key);
        }

        private ProjectFile uploadBytes(String filename, String mediaType, byte[] bytes, String key) {
            return ingestion.upload(new ProjectFileUploadCommand(project.id(), filename, mediaType, bytes.length,
                    new ByteArrayInputStream(bytes), key, META));
        }

        private List<ProjectFile> files() {
            return repositories.projectFileRepository().findByProjectId(project.id());
        }
    }
}
