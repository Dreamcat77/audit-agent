package com.fuquan.audit.contract.application.project;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.ProjectFileIngestionTransactionCoordinator.ProcessingOutcome;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectUploadConcurrencyTest {
    @TempDir Path root;

    @Test
    void concurrentRecoveryConvergesOnOneCompletedFileAndOneFactSet() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var pending = support.pending(true, false, null, null);
        ProjectFileRecoveryService recovery = support.recovery(support.durableStorage);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { start.await(); return recovery.recover(
                    support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META); });
            var second = workers.submit(() -> { start.await(); return recovery.recover(
                    support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META); });
            start.countDown();

            assertThat(first.get().id()).isEqualTo(second.get().id()).isEqualTo(pending.file().id());
            assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                    .isEmpty();
            assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                    .isNotEmpty().doesNotHaveDuplicates();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void existingArchiveChildRemapsParseResultCandidateAndFactsToDurableChildIdentity() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root.resolve("child-remap"));
        var pending = support.pending(true, false, null, null);
        UUID durableChildId = UUID.randomUUID();
        UUID proposedChildId = UUID.randomUUID();
        UUID durableParseResultId = UUID.randomUUID();
        String childSha = "a".repeat(64);
        ProjectFile durableChild = parsedChild(durableChildId, pending.file().id(), support.project.id(), childSha);
        ProjectFile proposedChild = parsedChild(proposedChildId, pending.file().id(), support.project.id(), childSha);
        support.repositories.projectFileRepository().insert(durableChild);
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("archive/page:1", "Contract No: CHILD-1", 1, null)));
        ProjectFileParseResult proposedResult = ProjectFileParseResultFactory.currentPipeline(
                proposedChild, parsed, null, 1, null,
                ProjectRecoveryTestSupport.NOW, ProjectRecoveryTestSupport.NOW);
        ProjectFileParseResult durableResult = new ProjectFileParseResult(durableParseResultId,
                proposedResult.projectId(), durableChildId, proposedResult.fileSha256(), proposedResult.parseVersion(),
                proposedResult.parseOrigin(), proposedResult.parseStatus(), proposedResult.parseMethod(),
                proposedResult.engine(), proposedResult.engineVersion(), proposedResult.canonicalText(),
                proposedResult.canonicalTextSha256(), proposedResult.pageCount(), proposedResult.durationMs(),
                proposedResult.confidence(), proposedResult.supersedesParseResultId(), proposedResult.createdAt(),
                proposedResult.completedAt(), proposedResult.versionNo());
        support.parseResults.insertIfAbsent(durableResult);
        List<ExtractionResult> proposedExtractions = new ProjectFactExtractor(
                new ExtractionKeyFactory(), support.clock).extract(proposedChild, parsed);
        ContractCandidate proposedCandidate = new ContractCandidateAssembler(support.clock).assemble(
                support.project.id(), proposedChild, proposedExtractions);
        proposedExtractions = proposedExtractions.stream().map(fact -> new ExtractionResult(
                fact.id(), fact.projectId(), fact.sourceFileId(), proposedCandidate.id(),
                fact.extractionKey(), fact.extractionVersion(), fact.factType(), fact.groupKey(),
                fact.valueText(), fact.normalizedValue(), fact.valueAmount(), fact.sourceText(),
                fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator(), fact.createdAt())).toList();
        ProcessingOutcome outcome = new ProcessingOutcome(
                pending.file().complete(ProjectFileParseStatus.NOT_APPLICABLE, null, null,
                        ProjectRecoveryTestSupport.NOW),
                List.of(proposedChild), List.of(proposedCandidate), proposedExtractions, List.of(proposedResult));

        support.coordinator.completeUpload(support.coordinator.resume(pending.record(), pending.file()),
                outcome, support.actor, ProjectRecoveryTestSupport.META);

        assertThat(support.parseResults.findLatestByFileId(proposedChildId)).isEmpty();
        assertThat(support.parseResults.findLatestByFileId(durableChildId)).hasValueSatisfying(result -> {
                    assertThat(result.fileId()).isEqualTo(durableChildId);
                    assertThat(result.canonicalText()).isEqualTo("Contract No: CHILD-1");
                });
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.sourceContractFileId()).isEqualTo(durableChildId);
                    assertThat(candidate.contractNo()).isEqualTo("CHILD-1");
                });
        UUID storedCandidateId = support.repositories.contractCandidateRepository()
                .findByProjectId(support.project.id()).get(0).id();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .isNotEmpty().allSatisfy(fact -> {
                    assertThat(fact.sourceFileId()).isEqualTo(durableChildId);
                    assertThat(fact.candidateId()).isEqualTo(storedCandidateId);
                    assertThat(fact.extractionKey()).isEqualTo(new ExtractionKeyFactoryV2().create(
                            durableChildId, durableParseResultId, fact.extractionVersion(), fact.factType(),
                            fact.groupKey(), fact.sourceLocator()));
                });
    }

    @Test
    void legacyOutcomeWithoutAnImmutableParseResultKeepsTheV1ExtractionKey() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root.resolve("legacy-v1-fallback"));
        var pending = support.pending(true, false, null, null);
        String locator = "page:1/fact:PROJECT_NO/occurrence:1";
        ExtractionResult legacyFact = new ExtractionResult(UUID.randomUUID(), support.project.id(), pending.file().id(),
                null, "a".repeat(64), "legacy-extraction-v1", "PROJECT_NO", null, "P-1", "P-1",
                (BigDecimal) null, "Project No: P-1", 1, null, locator, ProjectRecoveryTestSupport.NOW);
        ProcessingOutcome outcome = new ProcessingOutcome(
                pending.file().complete(ProjectFileParseStatus.NOT_APPLICABLE, null, null,
                        ProjectRecoveryTestSupport.NOW),
                List.of(), List.of(), List.of(legacyFact), List.of());

        support.coordinator.completeUpload(support.coordinator.resume(pending.record(), pending.file()),
                outcome, support.actor, ProjectRecoveryTestSupport.META);

        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .singleElement().extracting(ExtractionResult::extractionKey)
                .isEqualTo(new ExtractionKeyFactory().create(pending.file().id(), "legacy-extraction-v1",
                        "PROJECT_NO", null, locator));
    }

    @Test
    void legacyImportedImmutableParseResultUsesTheV2ExtractionKey() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root.resolve("legacy-v2"));
        var pending = support.pending(true, false, null, null);
        ProjectFile completed = pending.file().complete(ProjectFileParseStatus.PARSED, "Contract No: LEG-1", null,
                new ProjectFileParseProvenance(ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30",
                        null, 1, 5L), ProjectRecoveryTestSupport.NOW);
        ProjectFileParseResult legacyResult = ProjectFileParseResultFactory.legacyImported(completed, 1,
                null, ProjectRecoveryTestSupport.NOW, ProjectRecoveryTestSupport.NOW);
        String locator = "page:1/fact:PROJECT_NO/occurrence:1";
        ExtractionResult fact = new ExtractionResult(UUID.randomUUID(), support.project.id(), pending.file().id(),
                null, "a".repeat(64), "legacy-extraction-v1", "PROJECT_NO", null, "P-1", "P-1",
                (BigDecimal) null, "Project No: P-1", 1, null, locator, ProjectRecoveryTestSupport.NOW);
        ProcessingOutcome outcome = new ProcessingOutcome(
                completed,
                List.of(), List.of(), List.of(fact), List.of(legacyResult));

        support.coordinator.completeUpload(support.coordinator.resume(pending.record(), pending.file()),
                outcome, support.actor, ProjectRecoveryTestSupport.META);

        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .singleElement().extracting(ExtractionResult::extractionKey)
                .isEqualTo(new ExtractionKeyFactoryV2().create(pending.file().id(), legacyResult.parseResultId(),
                        "legacy-extraction-v1", "PROJECT_NO", null, locator));
    }

    @Test
    void parseResultInsertFailureCannotPublishParsedProjectFile() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root.resolve("parse-insert-failure"));
        var pending = support.pending(true, false, null, null);
        ProcessingOutcome outcome = support.ingestion.processPersisted(pending.file());
        ProjectFileParseResultRepository rejectingResults = new ProjectFileParseResultRepository() {
            @Override public ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result) {
                throw new IllegalStateException("synthetic parse result insert failure");
            }
            @Override public Optional<ProjectFileParseResult> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID id, int version) {
                return Optional.empty();
            }
            @Override public Optional<ProjectFileParseResult> findLatestByFileId(UUID id) { return Optional.empty(); }
        };
        ProjectFileIngestionTransactionCoordinator rejectingCoordinator =
                new ProjectFileIngestionTransactionCoordinator(
                        support.repositories.projectRepository(), support.repositories.projectFileRepository(),
                        support.repositories.contractCandidateRepository(),
                        support.repositories.extractionResultRepository(), rejectingResults, support.idempotency,
                        support.audits::add, support.clock, new FlowDebugLogger(new ObservabilityProperties()));

        assertThatThrownBy(() -> rejectingCoordinator.completeUpload(
                rejectingCoordinator.resume(pending.record(), pending.file()), outcome,
                support.actor, ProjectRecoveryTestSupport.META))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic parse result insert failure");

        assertThat(support.repositories.projectFileRepository()
                .findByProjectIdAndId(support.project.id(), pending.file().id())).hasValueSatisfying(file -> {
                    assertThat(file.ingestionStatus()).isEqualTo(ProjectFileIngestionStatus.UPLOAD_PENDING);
                    assertThat(file.parseStatus()).isEqualTo(ProjectFileParseStatus.PENDING);
                    assertThat(file.parsedText()).isNull();
                });
        assertThat(support.record(pending.file().id()).status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .isEmpty();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .isEmpty();
        assertThat(support.audits).extracting(event -> event.eventType())
                .containsExactly("PROJECT_FILE_UPLOAD_STARTED");
    }

    private static ProjectFile parsedChild(UUID id, UUID parentId, UUID projectId, String sha) {
        Instant now = ProjectRecoveryTestSupport.NOW;
        return new ProjectFile(id, projectId, parentId, "01_contract/contract.pdf", "contract.pdf",
                "application/pdf", ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, sha, sha, 100, "Contract No: CHILD-1", null, now, now,
                new ProjectFileParseProvenance(ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30",
                        null, 1, 5L));
    }
}
