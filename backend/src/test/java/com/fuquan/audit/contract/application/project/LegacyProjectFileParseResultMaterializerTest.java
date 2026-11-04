package com.fuquan.audit.contract.application.project;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LegacyProjectFileParseResultMaterializerTest {
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-08-30T10:15:30Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T10:15:35Z");
    private static final String FILE_SHA256 = "2".repeat(64);
    private static final String TEXT = "persisted contract text";
    private static final String TEXT_SHA256 =
            "7a29a01c304567c32da82bf26ae9513b60c8de46c6736dc2d79c7778d4dbb9d9";
    private static final ProjectFileParseProvenance PDF_PROVENANCE = new ProjectFileParseProvenance(
            ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30", null, 3, 25L);

    @Test
    void firstMaterializationCreatesVersionOneLegacyResultFromPersistedProjection() {
        ProjectFile file = parsedFile("legacy.pdf", ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, TEXT, PDF_PROVENANCE);
        InMemoryProjectFileParseResultRepository results = new InMemoryProjectFileParseResultRepository();

        ProjectFileParseResult materialized = materializer(file, results).materialize(PROJECT_ID, FILE_ID);

        assertThat(materialized.projectId()).isEqualTo(PROJECT_ID);
        assertThat(materialized.fileId()).isEqualTo(FILE_ID);
        assertThat(materialized.fileSha256()).isEqualTo(FILE_SHA256);
        assertThat(materialized.parseVersion()).isEqualTo(1);
        assertThat(materialized.parseOrigin()).isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
        assertThat(materialized.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(materialized.parseMethod()).isEqualTo(ProjectFileParseMethod.PDF_TEXT);
        assertThat(materialized.engine()).isEqualTo("PDFBOX_2.0.30");
        assertThat(materialized.engineVersion()).isNull();
        assertThat(materialized.canonicalText()).isEqualTo(TEXT);
        assertThat(materialized.canonicalTextSha256()).isEqualTo(TEXT_SHA256);
        assertThat(materialized.pageCount()).isEqualTo(3);
        assertThat(materialized.durationMs()).isEqualTo(25L);
        assertThat(materialized.confidence()).isNull();
        assertThat(materialized.supersedesParseResultId()).isNull();
        assertThat(materialized.createdAt()).isEqualTo(CREATED_AT);
        assertThat(materialized.completedAt()).isEqualTo(UPDATED_AT);
        assertThat(materialized.versionNo()).isEqualTo(1);
    }

    @Test
    void repeatedMaterializationReturnsTheSameDurableIdentity() {
        ProjectFile file = parsedPdf();
        InMemoryProjectFileParseResultRepository results = new InMemoryProjectFileParseResultRepository();
        LegacyProjectFileParseResultMaterializer materializer = materializer(file, results);

        ProjectFileParseResult first = materializer.materialize(PROJECT_ID, FILE_ID);
        ProjectFileParseResult replay = materializer.materialize(PROJECT_ID, FILE_ID);

        assertThat(replay.parseResultId()).isEqualTo(first.parseResultId());
        assertThat(results.findByFileIdAndParseVersion(FILE_ID, 1)).contains(first);
    }

    @Test
    void matchingExistingCurrentPipelineResultIsReusedWithoutLegacyInsert() {
        ProjectFile file = parsedPdf();
        ProjectFileParseResult existing = matchingExistingResult(ProjectFileParseOrigin.CURRENT_PIPELINE);
        ProjectFileParseResultRepository results = existingOnly(existing);

        ProjectFileParseResult returned = materializer(file, results).materialize(PROJECT_ID, FILE_ID);

        assertThat(returned).isSameAs(existing);
    }

    @Test
    void concurrentDistinctGeneratedIdsConvergeOnExactlyOneDurableWinner() throws Exception {
        ProjectFile file = parsedPdf();
        InMemoryProjectFileParseResultRepository durable = new InMemoryProjectFileParseResultRepository();
        ConcurrentLinkedQueue<UUID> attemptedIds = new ConcurrentLinkedQueue<>();
        CyclicBarrier initialReads = new CyclicBarrier(2);
        ProjectFileParseResultRepository racing = new ProjectFileParseResultRepository() {
            @Override
            public ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result) {
                attemptedIds.add(result.parseResultId());
                return durable.insertIfAbsent(result);
            }

            @Override public Optional<ProjectFileParseResult> findById(UUID id) { return durable.findById(id); }

            @Override
            public Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID id, int version) {
                Optional<ProjectFileParseResult> observed = durable.findByFileIdAndParseVersion(id, version);
                try {
                    initialReads.await(5, SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("concurrent initial reads did not meet", failure);
                }
                return observed;
            }

            @Override public Optional<ProjectFileParseResult> findLatestByFileId(UUID id) {
                return durable.findLatestByFileId(id);
            }
        };
        LegacyProjectFileParseResultMaterializer materializer = materializer(file, racing);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectFileParseResult> first = executor.submit(() -> materializer.materialize(PROJECT_ID, FILE_ID));
            Future<ProjectFileParseResult> second = executor.submit(() -> materializer.materialize(PROJECT_ID, FILE_ID));

            ProjectFileParseResult one = first.get(5, SECONDS);
            ProjectFileParseResult two = second.get(5, SECONDS);
            assertThat(attemptedIds).hasSize(2).doesNotHaveDuplicates();
            assertThat(two.parseResultId()).isEqualTo(one.parseResultId());
            assertThat(durable.findByFileIdAndParseVersion(FILE_ID, 1)).contains(one);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingServerOwnedProjectFileFailsAsNotFound() {
        InMemoryProjectFileParseResultRepository results = new InMemoryProjectFileParseResultRepository();
        LegacyProjectFileParseResultMaterializer materializer = new LegacyProjectFileParseResultMaterializer(
                files(null), results);

        assertThatThrownBy(() -> materializer.materialize(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.NOT_FOUND));
        assertThat(results.findLatestByFileId(FILE_ID)).isEmpty();
    }

    @Test
    void unavailableFileFailsClosedWithoutCreatingAResult() {
        assertIneligible(parsedFile("legacy.pdf", ProjectFileIngestionStatus.UPLOAD_PENDING,
                ProjectFileParseStatus.PARSED, TEXT, PDF_PROVENANCE),
                "project file parse result is unavailable");
    }

    @Test
    void nonParsedFileFailsClosedWithoutCreatingAResult() {
        assertIneligible(parsedFile("legacy.pdf", ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.FAILED, TEXT, PDF_PROVENANCE),
                "project file parse result is unavailable");
    }

    @ParameterizedTest
    @MethodSource("missingTexts")
    void missingOrBlankPersistedTextFailsClosed(String text) {
        assertIneligible(parsedFile("legacy.pdf", ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, text, PDF_PROVENANCE),
                "persisted project text is unavailable");
    }

    static Stream<Arguments> missingTexts() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of("   "));
    }

    @Test
    void missingPersistedProvenanceFailsClosed() {
        assertIneligible(parsedFile("legacy.pdf", ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, TEXT, ProjectFileParseProvenance.none()),
                "persisted parse provenance is inconsistent");
    }

    @ParameterizedTest
    @MethodSource("inconsistentProvenance")
    void parserMethodThatCannotProduceThePersistedSourceTypeFailsClosed(
            String filename, ProjectFileParseMethod method) {
        ProjectFileParseProvenance provenance = new ProjectFileParseProvenance(
                method, "PERSISTED_ENGINE", method == ProjectFileParseMethod.OCR
                        ? new BigDecimal("0.9000") : null, 1, 10L);

        assertIneligible(parsedFile(filename, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, TEXT, provenance),
                "persisted parse provenance is inconsistent");
    }

    static Stream<Arguments> inconsistentProvenance() {
        return Stream.of(
                Arguments.of("legacy.pdf", ProjectFileParseMethod.DOCX_TEXT),
                Arguments.of("legacy.docx", ProjectFileParseMethod.PDF_TEXT),
                Arguments.of("legacy.docx", ProjectFileParseMethod.OCR),
                Arguments.of("legacy.png", ProjectFileParseMethod.PDF_TEXT),
                Arguments.of("legacy.jpg", ProjectFileParseMethod.DOCX_TEXT));
    }

    @Test
    void existingResultWithWrongProjectOwnershipFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                UUID.randomUUID(), FILE_ID, FILE_SHA256, ProjectFileParseStatus.PARSED,
                TEXT, TEXT_SHA256, ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30");
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithWrongFileOwnershipFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                PROJECT_ID, UUID.randomUUID(), FILE_SHA256, ProjectFileParseStatus.PARSED,
                TEXT, TEXT_SHA256, ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30");
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithWrongFileShaFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                PROJECT_ID, FILE_ID, "3".repeat(64), ProjectFileParseStatus.PARSED,
                TEXT, TEXT_SHA256, ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30");
        assertExistingConflict(existing);
    }

    @Test
    void existingNonParsedResultFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                PROJECT_ID, FILE_ID, FILE_SHA256, ProjectFileParseStatus.FAILED,
                null, null, ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30");
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStaleCanonicalTextFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                PROJECT_ID, FILE_ID, FILE_SHA256, ProjectFileParseStatus.PARSED,
                "stale contract text", "91037b445d2ca7a91bdf69a1c7f3e2813d30945b7b35e8a34eacf41d445c85c5",
                ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30");
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStaleProvenanceFailsClosed() {
        ProjectFileParseResult existing = copy(matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                PROJECT_ID, FILE_ID, FILE_SHA256, ProjectFileParseStatus.PARSED,
                TEXT, TEXT_SHA256, ProjectFileParseMethod.PDF_TEXT, "OTHER_ENGINE");
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStaleParseMethodFailsClosed() {
        ProjectFileParseResult existing = copyProvenance(
                matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                ProjectFileParseMethod.DOCX_TEXT, 3, 25L, null);
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStalePageCountFailsClosed() {
        ProjectFileParseResult existing = copyProvenance(
                matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                ProjectFileParseMethod.PDF_TEXT, 4, 25L, null);
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStaleDurationFailsClosed() {
        ProjectFileParseResult existing = copyProvenance(
                matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                ProjectFileParseMethod.PDF_TEXT, 3, 26L, null);
        assertExistingConflict(existing);
    }

    @Test
    void existingResultWithStaleConfidenceFailsClosed() {
        ProjectFileParseResult existing = copyProvenance(
                matchingExistingResult(ProjectFileParseOrigin.LEGACY_IMPORTED),
                ProjectFileParseMethod.PDF_TEXT, 3, 25L, new BigDecimal("0.1000"));
        assertExistingConflict(existing);
    }

    private static LegacyProjectFileParseResultMaterializer materializer(
            ProjectFile file, ProjectFileParseResultRepository results) {
        return new LegacyProjectFileParseResultMaterializer(files(file), results);
    }

    private static ProjectFileRepository files(ProjectFile file) {
        return new ProjectFileRepository() {
            @Override public void insert(ProjectFile ignored) { throw new AssertionError("must not write file"); }
            @Override public void update(ProjectFile ignored) { throw new AssertionError("must not write file"); }
            @Override public Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID fileId) {
                return Optional.ofNullable(file)
                        .filter(value -> value.projectId().equals(projectId) && value.id().equals(fileId));
            }
            @Override public Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(
                    UUID projectId, UUID parentId, String path) { throw new AssertionError("unexpected query"); }
            @Override public List<ProjectFile> findByProjectId(UUID projectId) {
                throw new AssertionError("unexpected query");
            }
        };
    }

    private static ProjectFileParseResultRepository existingOnly(ProjectFileParseResult existing) {
        return new ProjectFileParseResultRepository() {
            @Override public ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result) {
                throw new AssertionError("existing result must be reused");
            }
            @Override public Optional<ProjectFileParseResult> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID id, int version) {
                return Optional.of(existing);
            }
            @Override public Optional<ProjectFileParseResult> findLatestByFileId(UUID id) {
                return Optional.of(existing);
            }
        };
    }

    private static ProjectFile parsedPdf() {
        return parsedFile("legacy.pdf", ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, TEXT, PDF_PROVENANCE);
    }

    private static ProjectFile parsedFile(String filename, ProjectFileIngestionStatus ingestionStatus,
            ProjectFileParseStatus parseStatus, String text, ProjectFileParseProvenance provenance) {
        return new ProjectFile(FILE_ID, PROJECT_ID, null, null, filename, "application/octet-stream",
                ProjectFileCategory.CONTRACT_BODY, ingestionStatus, parseStatus, "1".repeat(64),
                FILE_SHA256, 100, text, null, CREATED_AT, UPDATED_AT, provenance);
    }

    private static ProjectFileParseResult matchingExistingResult(ProjectFileParseOrigin origin) {
        return new ProjectFileParseResult(UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA256, 1,
                origin, ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30",
                null, TEXT, TEXT_SHA256, 3, 25L, null, null, CREATED_AT, UPDATED_AT, 1);
    }

    private static ProjectFileParseResult copy(ProjectFileParseResult base, UUID projectId, UUID fileId,
            String fileSha256, ProjectFileParseStatus status, String text, String textSha256,
            ProjectFileParseMethod method, String engine) {
        return new ProjectFileParseResult(base.parseResultId(), projectId, fileId, fileSha256,
                base.parseVersion(), base.parseOrigin(), status, method, engine, base.engineVersion(),
                text, textSha256, base.pageCount(), base.durationMs(), base.confidence(),
                base.supersedesParseResultId(), base.createdAt(), base.completedAt(), base.versionNo());
    }

    private static ProjectFileParseResult copyProvenance(ProjectFileParseResult base,
            ProjectFileParseMethod method, Integer pageCount, Long durationMs, BigDecimal confidence) {
        return new ProjectFileParseResult(base.parseResultId(), base.projectId(), base.fileId(),
                base.fileSha256(), base.parseVersion(), base.parseOrigin(), base.parseStatus(), method,
                base.engine(), base.engineVersion(), base.canonicalText(), base.canonicalTextSha256(),
                pageCount, durationMs, confidence, base.supersedesParseResultId(), base.createdAt(),
                base.completedAt(), base.versionNo());
    }

    private static void assertIneligible(ProjectFile file, String safeMessage) {
        InMemoryProjectFileParseResultRepository results = new InMemoryProjectFileParseResultRepository();
        assertThatThrownBy(() -> materializer(file, results).materialize(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                    assertThat(failure.getMessage()).isEqualTo(safeMessage)
                            .doesNotContain(file.originalFilename());
                    if (file.parsedText() != null && !file.parsedText().isBlank()) {
                        assertThat(failure.getMessage()).doesNotContain(file.parsedText());
                    }
                });
        assertThat(results.findLatestByFileId(FILE_ID)).isEmpty();
    }

    private static void assertExistingConflict(ProjectFileParseResult existing) {
        assertThatThrownBy(() -> materializer(parsedPdf(), existingOnly(existing))
                .materialize(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                    assertThat(failure.getMessage()).isEqualTo("immutable parse result is inconsistent");
                    assertThat(failure.getMessage()).doesNotContain("legacy.pdf", TEXT);
                });
    }
}
