package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocumentParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.integration.file.FileStorageProperties;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.integration.ocr.fake.FakeOcrAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProjectFileIngestionServiceTest {
    @TempDir Path storageRoot;

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="audit.word97.fixture",matches=".+")
    void genuineWordRootAndZipReuseOriginalStorageAndPublishBoundParseEvidence() throws Exception {
        Path fixture=Path.of(System.getProperty("audit.word97.fixture"));
        byte[] original=java.nio.file.Files.readAllBytes(fixture.resolve("真实Word97_软件采购合同.doc"));
        String originalSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        for(boolean archive:List.of(false,true)) {
            Clock clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC);
            var repositories=new InMemoryProjectRepositories();
            var parses=new InMemoryProjectFileParseResultRepository();
            var projects=new ProjectApplicationService(repositories.projectRepository(),repositories.projectFileRepository(),
                    repositories.contractCandidateRepository(),repositories.extractionResultRepository(),clock);
            var project=projects.createBlank("actor-string");
            var properties=new FileStorageProperties();properties.setRoot(storageRoot.resolve("word-"+archive).toString());
            var storage=new FileSystemStorageAdapter(properties);
            var intake=ProjectIntakeProperties.defaults();
            var policy=new ProjectFilePolicy(intake,Duration.ofMinutes(15));
            var coordinator=new ProjectFileIngestionTransactionCoordinator(repositories.projectRepository(),
                    repositories.projectFileRepository(),repositories.contractCandidateRepository(),
                    repositories.extractionResultRepository(),parses,new InMemoryIdempotencyAdapter(),event->{},clock,
                    new FlowDebugLogger(new ObservabilityProperties()));
            var archives=new ProjectArchiveProcessingService(new SelectedProjectArchiveReader(intake),
                    new ProjectFileClassifier(),policy,new ProjectArchiveStorageKeyFactory(),storage);
            var actor=new CurrentUser("actor-string","actor","Actor",Set.of("AUDITOR"));
            var ingestion=new ProjectFileIngestionService(projects,storage,policy,new ProjectFileClassifier(),
                    List.of(new com.fuquan.audit.contract.application.project.parse.ProjectDocTextParser(intake)),
                    new FakeOcrAdapter(),new ProjectFactExtractor(new ExtractionKeyFactory(),clock),
                    new ContractCandidateAssembler(clock),archives,coordinator,()->Optional.of(actor),clock);
            byte[] bytes=archive?java.nio.file.Files.readAllBytes(fixture.resolve("仅旧版DOC_解包解析兼容性检查.zip")):original;
            var uploaded=ingestion.upload(new ProjectFileUploadCommand(project.id(),archive?"legacy.zip":"legacy.doc",
                    archive?"application/zip":"application/msword",bytes.length,new ByteArrayInputStream(bytes),
                    "word-key-"+archive,new ProjectRequestMetadata("request","trace","127.0.0.1")));
            ProjectFile doc=archive?repositories.projectFileRepository().findByProjectId(project.id()).stream()
                    .filter(f->f.parentArchiveFileId()!=null).findFirst().orElseThrow():uploaded;
            assertThat(doc.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
            assertThat(doc.sha256()).isEqualTo(originalSha);
            assertThat(doc.sizeBytes()).isEqualTo(original.length);
            assertThat(doc.parseProvenance().method()).isEqualTo(ProjectFileParseMethod.DOC_TEXT);
            try(var stored=storage.open(new StorageKey(doc.storageKey())).inputStream()) {
                assertThat(java.util.Arrays.equals(stored.readAllBytes(),original)).isTrue();
            }
            var parse=parses.findLatestByFileId(doc.id()).orElseThrow();
            assertThat(parse.fileId()).isEqualTo(doc.id());
            assertThat(parse.fileSha256()).isEqualTo(originalSha);
            assertThat(parse.engineVersion()).isEqualTo("DOC_BODY_ROW_V1");
            assertThat(parse.canonicalText().equals(doc.parsedText())).isTrue();
            assertThat(parse.canonicalText().length()).isBetween(13000,14500);
            assertThat(repositories.extractionResultRepository().findBySourceFileId(project.id(),doc.id()))
                    .isNotEmpty().allSatisfy(fact->{
                        assertThat(fact.parseResultId()).isEqualTo(parse.parseResultId());
                        assertThat(fact.sourceFileId()).isEqualTo(doc.id());
                    });
        }
    }

    @Test
    void asyncAcceptancePublishesBytesAndOneQueuedIdentityWithoutInvokingParser() throws Exception {
        Clock clock=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
        var repositories=new InMemoryProjectRepositories();
        var parses=new InMemoryProjectFileParseResultRepository();
        var projects=new ProjectApplicationService(repositories.projectRepository(),repositories.projectFileRepository(),
                repositories.contractCandidateRepository(),repositories.extractionResultRepository(),clock);
        var project=projects.createBlank("actor-string");
        var properties=new FileStorageProperties();properties.setRoot(storageRoot.toString());
        var storage=new FileSystemStorageAdapter(properties);
        var policy=new ProjectFilePolicy(ProjectIntakeProperties.defaults(),Duration.ofMinutes(15));
        var coordinator=new ProjectFileIngestionTransactionCoordinator(repositories.projectRepository(),
                repositories.projectFileRepository(),repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(),parses,new InMemoryIdempotencyAdapter(),event->{},clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        var archives=new ProjectArchiveProcessingService(new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()),
                new ProjectFileClassifier(),policy,new ProjectArchiveStorageKeyFactory(),storage);
        var calls=new AtomicInteger();
        ProjectDocumentParser parser=new ProjectDocumentParser() {
            public boolean supports(ProjectFile file){return true;}
            public ParsedProjectDocument parse(ProjectFile file,java.io.InputStream input){
                calls.incrementAndGet();return new ParsedProjectDocument(List.of(new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:1","Synthetic contract amount: 42.50")));
            }
        };
        var actor=new CurrentUser("actor-string","actor","Actor",Set.of("AUDITOR"));
        var ingestion=new ProjectFileIngestionService(projects,storage,policy,new ProjectFileClassifier(),List.of(parser),
                new FakeOcrAdapter(),new ProjectFactExtractor(new ExtractionKeyFactory(),clock),
                new ContractCandidateAssembler(clock),archives,coordinator,()->Optional.of(actor),clock);
        var jobs=new com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileJobRepository();
        byte[] bytes=emptyDocx();
        var first=ingestion.acceptAsync(new ProjectFileUploadCommand(project.id(),"synthetic.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes.length,
                new ByteArrayInputStream(bytes),"async-key",new ProjectRequestMetadata("req","trace","127.0.0.1")),jobs);
        assertThat(first.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(first.parseStatus()).isEqualTo(ProjectFileParseStatus.PENDING);
        assertThat(calls).hasValue(0);
        assertThat(parses.findLatestByFileId(first.id())).isEmpty();
        var job=jobs.byFile(project.id(),first.id()).orElseThrow();
        var replay=ingestion.acceptAsync(new ProjectFileUploadCommand(project.id(),"synthetic.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes.length,
                new ByteArrayInputStream(bytes),"async-key",new ProjectRequestMetadata("req-2","trace-2","127.0.0.1")),jobs);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jobs.byProject(project.id())).hasSize(1);
        var processing=new ProjectFileProcessingService(ingestion,repositories.projectFileRepository(),parses,
                storage,coordinator,jobs,clock);
        processing.process(job.id(),actor,token->{});
        assertThat(calls).hasValue(1);
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(repositories.projectFileRepository().findByProjectIdAndId(project.id(),first.id()).orElseThrow()
                .parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(parses.findLatestByFileId(first.id())).isPresent();
        processing.process(job.id(),actor,token->{});
        assertThat(calls).hasValue(1);
    }

    @Test
    void parsedUploadPublishesOneImmutableResultFromTheSameParserOutputAsLegacyFacts() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        InMemoryProjectFileParseResultRepository parseResults =
                new InMemoryProjectFileParseResultRepository();
        ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), clock);
        Project project = projects.createBlank("actor-string");
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        FileStorageProperties storageProperties = new FileStorageProperties();
        storageProperties.setRoot(storageRoot.resolve("immutable-result").toString());
        FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
        ProjectFilePolicy policy = new ProjectFilePolicy(ProjectIntakeProperties.defaults(), Duration.ofMinutes(15));
        ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                parseResults, idempotency, event -> { }, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()), new ProjectFileClassifier(),
                policy, new ProjectArchiveStorageKeyFactory(), storage);
        String parserText = "Contract No: SYN-1 Contract Amount: 42.50";
        AtomicInteger parserCalls = new AtomicInteger();
        ProjectDocumentParser parser = new ProjectDocumentParser() {
            @Override public boolean supports(ProjectFile file) { return true; }
            @Override public ParsedProjectDocument parse(ProjectFile file, java.io.InputStream input) {
                parserCalls.incrementAndGet();
                return new ParsedProjectDocument(List.of(
                        new ParsedProjectDocument.Segment("pdf/page:1", parserText, 1, null)));
            }
        };
        CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
        ProjectIntakeProgressionService progression = mock(ProjectIntakeProgressionService.class);
        ProjectFileIngestionService service = new ProjectFileIngestionService(projects, storage, policy,
                new ProjectFileClassifier(), List.of(parser), new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock), new ContractCandidateAssembler(clock),
                archives, coordinator, progression, () -> Optional.of(actor), clock);

        byte[] uploadBytes = pdf("ignored by synthetic parser");
        ProjectFile uploaded = service.upload(new ProjectFileUploadCommand(project.id(), "001.pdf",
                "application/pdf", uploadBytes.length, new ByteArrayInputStream(uploadBytes),
                "parse-result-key", new ProjectRequestMetadata("request", "trace", "127.0.0.1")));

        assertThat(parserCalls).hasValue(1);
        verify(progression).ensureRoleAssessments(project.id(),
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), actor);
        assertThat(uploaded.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(uploaded.parsedText()).isEqualTo(parserText);
        assertThat(uploaded.parseProvenance().method()).isEqualTo(ProjectFileParseMethod.PDF_TEXT);
        assertThat(parseResults.findLatestByFileId(uploaded.id())).hasValueSatisfying(result -> {
            assertThat(result.parseOrigin()).isEqualTo(ProjectFileParseOrigin.CURRENT_PIPELINE);
            assertThat(result.parseVersion()).isEqualTo(1);
            assertThat(result.canonicalText()).isEqualTo(parserText);
            assertThat(result.canonicalTextSha256())
                    .isEqualTo("028d4c49957dee94508001a29282ea8197dca1d4c601ffc5c1cc711275218c0d");
            assertThat(result.fileSha256()).isEqualTo(uploaded.sha256());
        });
        assertThat(repositories.extractionResultRepository().findBySourceFileId(project.id(), uploaded.id()))
                .extracting(ExtractionResult::factType)
                .containsExactlyInAnyOrder("CONTRACT_NO", "AMOUNT_NUMERIC");
        assertThat(repositories.extractionResultRepository().findBySourceFileId(project.id(), uploaded.id()))
                .allSatisfy(fact -> assertThat(parserText).contains(fact.sourceText()));
        assertThat(repositories.extractionResultRepository().findBySourceFileId(project.id(), uploaded.id()))
                .allSatisfy(fact -> assertThat(fact.parseResultId()).isEqualTo(
                        parseResults.findLatestByFileId(uploaded.id()).orElseThrow().parseResultId()));
        assertThat(repositories.contractCandidateRepository().findByProjectId(project.id())).isEmpty();
    }

    @Test
    void validTextEmptyDocxCompletesAvailableAsParseFailureWithoutImmutableResult() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        InMemoryProjectFileParseResultRepository parseResults =
                new InMemoryProjectFileParseResultRepository();
        ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), clock);
        Project project = projects.createBlank("actor-string");
        FileStorageProperties storageProperties = new FileStorageProperties();
        storageProperties.setRoot(storageRoot.resolve("empty-docx").toString());
        FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
        ProjectIntakeProperties intake = ProjectIntakeProperties.defaults();
        ProjectFilePolicy policy = new ProjectFilePolicy(intake, Duration.ofMinutes(15));
        ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                parseResults, new InMemoryIdempotencyAdapter(), event -> { }, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(intake), new ProjectFileClassifier(), policy,
                new ProjectArchiveStorageKeyFactory(), storage);
        ProjectDocxContainerGuard guard = new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(intake), intake);
        CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
        ProjectFileIngestionService service = new ProjectFileIngestionService(projects, storage, policy,
                new ProjectFileClassifier(), List.of(new ProjectDocxTextParser(guard)), new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock), new ContractCandidateAssembler(clock),
                archives, coordinator, () -> Optional.of(actor), clock);
        byte[] bytes = emptyDocx();
        ProjectFileUploadCommand command = new ProjectFileUploadCommand(project.id(), "empty.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.length,
                new ByteArrayInputStream(bytes), "empty-docx-key",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));

        ProjectFile uploaded = service.upload(command);

        assertThat(uploaded.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(uploaded.parseStatus()).isEqualTo(ProjectFileParseStatus.FAILED);
        assertThat(uploaded.safeReasonCode()).isEqualTo("PARSE_EMPTY_RESULT");
        assertThat(uploaded.parsedText()).isNull();
        assertThat(parseResults.findLatestByFileId(uploaded.id())).isEmpty();
        assertThat(repositories.contractCandidateRepository().findByProjectId(project.id())).isEmpty();
        assertThat(repositories.extractionResultRepository().findByProjectId(project.id())).isEmpty();
    }

    @Test
    void directPdfUploadPublishesDurableFactsAndSameKeyReplaysSameFile() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), clock);
        Project project = projects.createBlank("actor-string");
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        List<OperationAuditEvent> audits = new ArrayList<>();
        FileStorageProperties storageProperties = new FileStorageProperties();
        storageProperties.setRoot(storageRoot.toString());
        FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
        ProjectFilePolicy policy = new ProjectFilePolicy(ProjectIntakeProperties.defaults(), Duration.ofMinutes(15));
        ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                new InMemoryProjectFileParseResultRepository(), idempotency, audits::add, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()), new ProjectFileClassifier(),
                policy, new ProjectArchiveStorageKeyFactory(), storage);
        CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
        ProjectFileIngestionService service = new ProjectFileIngestionService(projects, storage, policy,
                new ProjectFileClassifier(), List.of(new ProjectPdfTextParser()),
                new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock), new ContractCandidateAssembler(clock),
                archives, coordinator, () -> Optional.of(actor), clock);
        byte[] bytes = pdf("Contract No C-1 Contract Amount: 42.50");
        ProjectFileUploadCommand firstCommand = command(project, bytes, "upload-key");

        ProjectFile first = service.upload(firstCommand);
        ProjectFile replay = service.upload(command(project, bytes, "upload-key"));
        byte[] unknownBytes = pdf("Purchaser: Buyer University");
        ProjectFile unknown = service.upload(new ProjectFileUploadCommand(project.id(), "notes.pdf",
                "application/pdf", unknownBytes.length, new ByteArrayInputStream(unknownBytes), "unknown-key",
                new ProjectRequestMetadata("request-2", "trace-2", "127.0.0.1")));

        assertThat(first.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(unknown.category()).isEqualTo(ProjectFileCategory.UNKNOWN);
        assertThat(repositories.contractCandidateRepository().findByProjectId(project.id())).isEmpty();
        assertThat(repositories.extractionResultRepository().findByProjectId(project.id()))
                .extracting(value -> value.factType()).contains("PURCHASER");
        assertThat(idempotency.size()).isEqualTo(2);
        assertThat(audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_COMPLETED",
                        "PROJECT_FILE_UPLOAD_STARTED", "PROJECT_FILE_UPLOAD_COMPLETED");
    }

    @Test
    void explicitRecoveryReopensPersistedKeyAndDoesNotClaimANewOperation() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), clock);
        Project project = projects.createBlank("actor-string");
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        List<OperationAuditEvent> audits = new ArrayList<>();
        FileStorageProperties storageProperties = new FileStorageProperties(); storageProperties.setRoot(storageRoot.toString());
        FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
        ProjectFilePolicy policy = new ProjectFilePolicy(ProjectIntakeProperties.defaults(), Duration.ofMinutes(15));
        ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                new InMemoryProjectFileParseResultRepository(), idempotency, audits::add, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()), new ProjectFileClassifier(),
                policy, new ProjectArchiveStorageKeyFactory(), storage);
        CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
        ProjectFileIngestionService ingestion = new ProjectFileIngestionService(projects, storage, policy,
                new ProjectFileClassifier(), List.of(new ProjectPdfTextParser()),
                new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock), new ContractCandidateAssembler(clock),
                archives, coordinator, () -> Optional.of(actor), clock);
        byte[] bytes = pdf("Contract No REC-1 Contract Amount: 12.00");
        StagedFile staged = storage.stage(new ByteArrayInputStream(bytes), ProjectIntakeProperties.defaults().maxUploadSize().toBytes());
        java.util.UUID fileId = java.util.UUID.randomUUID();
        StorageKey key = new ProjectFileStorageKeyFactory().create(fileId, staged.sha256());
        ProjectFile pending = new ProjectFile(fileId, project.id(), null, null, "contract.pdf", "application/pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.UPLOAD_PENDING,
                ProjectFileParseStatus.PENDING, key.value(), staged.sha256(), staged.size(), null, null,
                clock.instant(), clock.instant());
        coordinator.startUpload(pending, actor, "original-key", "original-fingerprint",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
        storage.promote(staged, key);
        ProjectFileRecoveryService recovery = new ProjectFileRecoveryService(repositories.projectFileRepository(),
                storage, policy, coordinator, ingestion, () -> Optional.of(actor), clock);

        ProjectFile recovered = recovery.recover(project.id(), fileId,
                new ProjectRequestMetadata("recovery-request", "recovery-trace", "127.0.0.1"));

        assertThat(recovered.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.AVAILABLE);
        assertThat(idempotency.size()).isEqualTo(1);
        assertThat(repositories.contractCandidateRepository().findByProjectId(project.id())).isEmpty();
    }

    @Test
    void directDocxTableUploadPublishesPotentialLineItemFactsWithoutCandidate() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService projects = new ProjectApplicationService(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), clock);
        Project project = projects.createBlank("actor-string");
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        List<OperationAuditEvent> audits = new ArrayList<>();
        FileStorageProperties storageProperties = new FileStorageProperties();
        storageProperties.setRoot(storageRoot.toString());
        FileSystemStorageAdapter storage = new FileSystemStorageAdapter(storageProperties);
        ProjectIntakeProperties intake = ProjectIntakeProperties.defaults();
        ProjectFilePolicy policy = new ProjectFilePolicy(intake, Duration.ofMinutes(15));
        ProjectFileIngestionTransactionCoordinator coordinator = new ProjectFileIngestionTransactionCoordinator(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                new InMemoryProjectFileParseResultRepository(), idempotency, audits::add, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(intake), new ProjectFileClassifier(), policy,
                new ProjectArchiveStorageKeyFactory(), storage);
        CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
        ProjectDocxContainerGuard guard = new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(intake), intake);
        ProjectFileIngestionService service = new ProjectFileIngestionService(projects, storage, policy,
                new ProjectFileClassifier(), List.of(new ProjectDocxTextParser(guard)), new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock), new ContractCandidateAssembler(clock),
                archives, coordinator, () -> Optional.of(actor), clock);
        byte[] bytes = lineItemDocx();
        ProjectFileUploadCommand command = new ProjectFileUploadCommand(project.id(), "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.length,
                new ByteArrayInputStream(bytes), "docx-upload-key",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));

        service.upload(command);

        assertThat(repositories.contractCandidateRepository().findByProjectId(project.id())).isEmpty();
        assertThat(repositories.extractionResultRepository().findByProjectId(project.id()))
                .filteredOn(fact -> fact.factType().startsWith("LINE_ITEM_"))
                .hasSize(15)
                .extracting(ExtractionResult::groupKey)
                .containsOnly("SOFT-01", "SERV-01", "TRAIN-01");
    }

    private static ProjectFileUploadCommand command(Project project, byte[] bytes, String key) {
        return new ProjectFileUploadCommand(project.id(), "contract.pdf", "application/pdf",
                bytes.length, new ByteArrayInputStream(bytes), key,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700); content.showText(text); content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }

    private static byte[] lineItemDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(4, 5);
            String[][] values = {
                    {"标识", "货物/服务名称", "数量", "单价（元）", "申报金额（元）"},
                    {"SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00"},
                    {"SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00"},
                    {"TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00"}
            };
            for (int row = 0; row < values.length; row++) {
                for (int cell = 0; cell < values[row].length; cell++) {
                    table.getRow(row).getCell(cell).setText(values[row][cell]);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] emptyDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }
}
