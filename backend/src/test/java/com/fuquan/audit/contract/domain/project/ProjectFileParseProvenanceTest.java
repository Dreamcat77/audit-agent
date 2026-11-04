package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectFileParseProvenanceTest {

    @Test
    void parsedFileCarriesValidatedExtractionProvenance() {
        ProjectFileParseProvenance provenance = new ProjectFileParseProvenance(
                ProjectFileParseMethod.OCR, "PADDLEOCR_3.7.0",
                new BigDecimal("0.9876"), 3, 245L);
        ProjectFile parsed = pending().complete(ProjectFileParseStatus.PARSED,
                "识别文本", null, provenance, Instant.parse("2026-08-31T00:00:01Z"));

        assertThat(parsed.parseProvenance()).isEqualTo(provenance);
        assertThat(parsed.parseProvenance().confidence()).isEqualByComparingTo("0.9876");
    }

    @Test
    void provenanceRejectsImpossibleConfidenceCountsAndIncompleteIdentity() {
        assertThatThrownBy(() -> new ProjectFileParseProvenance(
                ProjectFileParseMethod.OCR, "PADDLEOCR_3.7.0", new BigDecimal("1.0001"), 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectFileParseProvenance(
                ProjectFileParseMethod.OCR, "PADDLEOCR_3.7.0", new BigDecimal("-0.0001"), 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectFileParseProvenance(
                ProjectFileParseMethod.PDF_TEXT, "PDFBOX_2.0.30", null, -1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectFileParseProvenance(
                ProjectFileParseMethod.DOCX_TEXT, "APACHE_POI_4.1.2", null, 1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectFileParseProvenance(
                null, "PADDLEOCR_3.7.0", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existingRowsHaveAnExplicitEmptyProvenanceValue() {
        assertThat(pending().parseProvenance()).isEqualTo(ProjectFileParseProvenance.none());
        assertThat(ProjectFileParseProvenance.none().present()).isFalse();
    }

    private static ProjectFile pending() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        String digest = "a".repeat(64);
        return new ProjectFile(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "scan.pdf", "application/pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.UPLOAD_PENDING, ProjectFileParseStatus.PENDING,
                digest, digest, 128L, null, null, now, now);
    }
}
