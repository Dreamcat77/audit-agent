package com.fuquan.audit.contract.domain.project;

import com.fuquan.audit.contract.application.project.ProjectFileParseResultFactory;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileParseResultTest {
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID FILE_ID = UUID.randomUUID();
    private static final String FILE_SHA = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void currentPipelineFactoryCopiesIdentityProvenanceAndDerivesLowerCaseUtf8Hash() {
        ProjectFile file = file("合同扫描件.png", FILE_SHA);
        ParsedProjectDocument document = new ParsedProjectDocument(
                java.util.List.of(new ParsedProjectDocument.Segment("p1", "项目名称：数据中心项目")));

        ProjectFileParseResult result = ProjectFileParseResultFactory.currentPipeline(
                file, document, "PADDLEOCR_3.7.0", 1, null, NOW, NOW);

        assertThat(result.projectId()).isEqualTo(PROJECT_ID);
        assertThat(result.fileId()).isEqualTo(FILE_ID);
        assertThat(result.fileSha256()).isEqualTo(FILE_SHA);
        assertThat(result.parseOrigin()).isEqualTo(ProjectFileParseOrigin.CURRENT_PIPELINE);
        assertThat(result.parseStatus()).isEqualTo(ProjectFileParseStatus.PARSED);
        assertThat(result.parseMethod()).isEqualTo(ProjectFileParseMethod.OCR);
        assertThat(result.engine()).isEqualTo("PaddleOCR");
        assertThat(result.engineVersion()).isEqualTo("PADDLEOCR_3.7.0");
        assertThat(result.canonicalTextSha256()).isEqualTo(sha256(document.fullText()));
        assertThat(result.canonicalTextSha256()).matches("[0-9a-f]{64}");
        assertThat(result.parseVersion()).isPositive();
        assertThat(result.confidence()).isEqualByComparingTo("0.98");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.durationMs()).isEqualTo(42L);
    }

    @Test
    void legacyFactoryUsesPersistedProjectionWithoutMutation() {
        ProjectFile file = file("legacy.pdf", "b".repeat(64));
        ProjectFileParseResult result = ProjectFileParseResultFactory.legacyImported(
                file, 1, null, NOW, NOW);

        assertThat(result.parseOrigin()).isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
        assertThat(result.canonicalText()).isEqualTo(file.parsedText());
        assertThat(result.canonicalTextSha256()).isEqualTo(sha256(file.parsedText()));
    }

    @Test
    void parsedResultRequiresCanonicalTextAndMatchingFileHash() {
        assertThatThrownBy(() -> new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.OCR, "OCR", "1", "", sha256(""),
                null, null, BigDecimal.ONE, null, NOW, NOW, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.OCR, "OCR", "1", "text", "a".repeat(64),
                null, null, BigDecimal.ONE, null, NOW, NOW, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA.toUpperCase(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.OCR, "OCR", "1", "text", "a".repeat(64),
                null, null, BigDecimal.ONE, null, NOW, NOW, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidencePageDurationAndVersionMustBeValid() {
        assertThatThrownBy(() -> result(0, BigDecimal.ONE, 0, 0L, NOW, "text", sha256("text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1, new BigDecimal("1.01"), 0, 0L, NOW, "text", sha256("text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1, BigDecimal.ONE, -1, 0L, NOW, "text", sha256("text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1, BigDecimal.ONE, 0, -1L, NOW, "text", sha256("text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1, BigDecimal.ONE, 0, 0L, null, "text", sha256("text")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                null, "OCR", "1", "text", sha256("text"),
                0, 0L, BigDecimal.ONE, null, NOW, NOW, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA, 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.OCR, "", "1", "text", sha256("text"),
                0, 0L, BigDecimal.ONE, null, NOW, NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);

        // versionNo is a storage row version and zero is valid for initial records.
        assertThat(result(1, BigDecimal.ONE, 0, 0L, NOW, "text", sha256("text")).versionNo()).isZero();
    }

    private static ProjectFileParseResult result(int parseVersion, BigDecimal confidence, Integer pageCount,
                                                  Long durationMs, Instant completedAt, String text, String hash) {
        return new ProjectFileParseResult(
                UUID.randomUUID(), PROJECT_ID, FILE_ID, FILE_SHA, parseVersion,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.OCR, "OCR", "1", text, hash,
                pageCount, durationMs, confidence, null, NOW, completedAt, 0);
    }

    private static ProjectFile file(String name, String sha) {
        return new ProjectFile(FILE_ID, PROJECT_ID, null, name, name, "image/png",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, sha, sha, 10, "项目名称：数据中心项目",
                null, NOW, NOW, new ProjectFileParseProvenance(
                ProjectFileParseMethod.OCR, "PaddleOCR", new BigDecimal("0.980"), 1, 42L));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
