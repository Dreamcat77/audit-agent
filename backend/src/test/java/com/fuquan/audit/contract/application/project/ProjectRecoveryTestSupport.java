package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.integration.file.FileStorageProperties;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.integration.ocr.fake.FakeOcrAdapter;
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
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

final class ProjectRecoveryTestSupport {
    static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
    final InMemoryProjectFileParseResultRepository parseResults =
            new InMemoryProjectFileParseResultRepository();
    final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    final List<OperationAuditEvent> audits = new ArrayList<>();
    final CurrentUser actor = new CurrentUser("actor-string", "actor", "Actor", Set.of("AUDITOR"));
    final FileSystemStorageAdapter durableStorage;
    final ProjectFilePolicy policy = new ProjectFilePolicy(ProjectIntakeProperties.defaults(), Duration.ofMinutes(15));
    final ProjectApplicationService projects;
    final ProjectFileIngestionTransactionCoordinator coordinator;
    final ProjectFileIngestionService ingestion;
    final Project project;
    private final byte[] pdf;

    ProjectRecoveryTestSupport(Path storageRoot) throws Exception {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setRoot(storageRoot.toString());
        durableStorage = new FileSystemStorageAdapter(properties);
        projects = new ProjectApplicationService(repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(), clock);
        coordinator = new ProjectFileIngestionTransactionCoordinator(repositories.projectRepository(),
                repositories.projectFileRepository(), repositories.contractCandidateRepository(),
                repositories.extractionResultRepository(), parseResults, idempotency, audits::add, clock,
                new FlowDebugLogger(new ObservabilityProperties()));
        ingestion = ingestion(durableStorage, coordinator);
        project = projects.createBlank(actor.userId());
        pdf = pdf("Contract No: REC-1 Contract Amount: 12.00");
    }

    ProjectFileIngestionService ingestion(FileStoragePort storage) {
        return ingestion(storage, coordinator);
    }

    ProjectFileIngestionService ingestion(
            FileStoragePort storage, ProjectFileIngestionTransactionCoordinator transactionCoordinator) {
        ProjectArchiveProcessingService archives = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()), new ProjectFileClassifier(),
                policy, new ProjectArchiveStorageKeyFactory(), storage);
        return new ProjectFileIngestionService(projects, storage, policy, new ProjectFileClassifier(),
                List.of(new ProjectPdfTextParser()), new FakeOcrAdapter(),
                new ProjectFactExtractor(new ExtractionKeyFactory(), clock),
                new ContractCandidateAssembler(clock), archives, transactionCoordinator,
                () -> Optional.of(actor), clock);
    }

    ProjectFileRecoveryService recovery(FileStoragePort storage) {
        return recovery(storage, coordinator);
    }

    ProjectFileRecoveryService recovery(
            FileStoragePort storage, ProjectFileIngestionTransactionCoordinator transactionCoordinator) {
        return new ProjectFileRecoveryService(repositories.projectFileRepository(), storage, policy,
                transactionCoordinator, ingestion(storage, transactionCoordinator),
                () -> Optional.of(actor), clock);
    }

    Pending pending(boolean publish, boolean stale, String persistedHash, Long persistedSize) {
        StagedFile staged = durableStorage.stage(new ByteArrayInputStream(pdf), policy.properties().maxUploadSize().toBytes());
        UUID fileId = UUID.randomUUID();
        StorageKey key = new ProjectFileStorageKeyFactory().create(fileId, staged.sha256());
        Instant created = stale ? NOW.minus(Duration.ofMinutes(16)) : NOW;
        ProjectFile file = new ProjectFile(fileId, project.id(), null, null, "contract.pdf", "application/pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.UPLOAD_PENDING,
                ProjectFileParseStatus.PENDING, key.value(), persistedHash == null ? staged.sha256() : persistedHash,
                persistedSize == null ? staged.size() : persistedSize, null, null, created, created);
        var start = coordinator.startUpload(file, actor, "key-" + fileId, "fingerprint", META);
        if (publish) durableStorage.promote(staged, key); else durableStorage.discard(staged);
        return new Pending(file, start.idempotency());
    }

    IdempotencyRecord record(UUID fileId) { return coordinator.findOriginal(fileId).orElseThrow(); }

    ProjectFileUploadCommand command(String operationKey) {
        return new ProjectFileUploadCommand(project.id(), "contract.pdf", "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf), operationKey, META);
    }

    record Pending(ProjectFile file, IdempotencyRecord record) { }

    static class StorageDecorator implements FileStoragePort {
        final FileStoragePort delegate;
        StorageDecorator(FileStoragePort delegate) { this.delegate = delegate; }
        @Override public StagedFile stage(java.io.InputStream input, long limit) { return delegate.stage(input, limit); }
        @Override public void promote(StagedFile staged, StorageKey key) { delegate.promote(staged, key); }
        @Override public StoredFileContent open(StorageKey key) { return delegate.open(key); }
        @Override public void discard(StagedFile staged) { delegate.discard(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { return delegate.inspect(key); }
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700); content.showText(text); content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
