package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityComparisonOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicProjectIdentityResolverTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String TEXT = "项目编号 GZU-2026-001 项目名称 贵州大学实验室设备采购项目";
    private final DeterministicProjectIdentityResolver resolver = new DeterministicProjectIdentityResolver();
    private final UUID projectId = UUID.randomUUID(), fileId = UUID.randomUUID(), parseId = UUID.randomUUID();
    private final ProjectFile file = new ProjectFile(fileId, projectId, null, null, "001.pdf", "application/pdf",
            ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
            sha("file"), sha("file"), 100, TEXT, null, NOW, NOW);
    private final ProjectFileParseResult parse = new ProjectFileParseResult(parseId, projectId, fileId,
            file.sha256(), 1, ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
            ProjectFileParseMethod.PDF_TEXT, "pdfbox", "v1", TEXT, sha(TEXT), 1, 1L,
            null, null, NOW, NOW, 0);

    @Test
    void exactNumberOrNameMatchIsOnlyLikelyAndMissingOtherFieldIsNotConflict() {
        var number = fact("PROJECT_NO", "ＧＺＵ－２０２６－００１");
        var result = resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 1), List.of(number));
        assertThat(result.assessment().status()).isEqualTo(ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT);
        assertThat(result.evidence()).extracting(value -> value.comparisonOutcome())
                .contains(ProjectIdentityComparisonOutcome.MATCH, ProjectIdentityComparisonOutcome.MISSING_FILE_VALUE);
        assertThat(result.evidence().stream().filter(value -> value.extractionResultId() != null).findFirst()
                .orElseThrow().extractionResultId()).isEqualTo(number.id());
    }

    @Test
    void oneStrongMismatchBeatsAnotherStrongMatch() {
        var result = resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 1),
                List.of(fact("PROJECT_NO", "OTHER-2026-999"),
                        fact("PROJECT_NAME", "贵州大学实验室设备采购项目")));
        assertThat(result.assessment().status()).isEqualTo(ProjectIdentityAssessmentStatus.CONFLICT);
        assertThat(result.evidence()).extracting(value -> value.comparisonOutcome())
                .contains(ProjectIdentityComparisonOutcome.MISMATCH, ProjectIdentityComparisonOutcome.MATCH);
    }

    @Test
    void absentComparableFactsAndUnconfirmedMetadataAreInsufficient() {
        assertThat(resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 1),
                List.of(fact("AWARD_SUPPLIER", "供应商甲"))).assessment().status())
                .isEqualTo(ProjectIdentityAssessmentStatus.INSUFFICIENT);
        assertThat(resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 0),
                List.of(fact("PROJECT_NO", "OTHER-2026-999"))).assessment().status())
                .isEqualTo(ProjectIdentityAssessmentStatus.INSUFFICIENT);
    }

    @Test
    void staleOrForeignParseFactsFailClosedRatherThanBecomingIdentityEvidence() {
        var foreign = new ExtractionResult(UUID.randomUUID(), projectId, fileId, UUID.randomUUID(), null,
                sha("foreign-key"), "facts-v2", "PROJECT_NO", null, "GZU-2026-001", null, null,
                "项目编号 GZU-2026-001", 1, 1, "paragraph:1", NOW);
        assertThatThrownBy(() -> resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 1),
                List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityEvidencePreservesFullServerOwnedExtractionLocatorWithinExistingFiveHundredCharLimit() {
        String locator = "pdf/page:1/paragraph:" + "synthetic-segment-".repeat(19);
        assertThat(locator.length()).isBetween(241, 500);
        var observed = new ExtractionResult(UUID.randomUUID(), projectId, fileId, parseId, null,
                sha("long-locator-key"), "facts-v2", "PROJECT_NO", null, "GZU-2026-001", null, null,
                TEXT, 1, 1, locator, NOW);
        var result = resolve(project("贵州大学实验室设备采购项目", "GZU-2026-001", 1), List.of(observed));
        assertThat(result.evidence().stream().filter(value -> observed.id().equals(value.extractionResultId()))
                .findFirst().orElseThrow().sourceLocator()).isEqualTo(locator);
    }

    @Test
    void generatedIntakeNameDoesNotCreateFalseProjectConflict() {
        var result = resolve(project("自动整理项目-12345678", null, 1),
                List.of(fact("PROJECT_NAME", "贵州大学实验室设备采购项目")));
        assertThat(result.assessment().status()).isEqualTo(ProjectIdentityAssessmentStatus.INSUFFICIENT);
    }

    private IdentityAssessmentCalculation resolve(Project project, List<ExtractionResult> facts) {
        return resolver.resolve(project, file, parse, facts, UUID.randomUUID(), sha("fingerprint"), NOW);
    }
    private Project project(String name, String no, long version) {
        return new Project(projectId, name, no, null, "actor", version, NOW, NOW);
    }
    private ExtractionResult fact(String type, String value) {
        return new ExtractionResult(UUID.randomUUID(), projectId, fileId, parseId, null,
                sha(type + value), "facts-v2", type, null, value, null, null,
                type + " " + value, 1, 1, "paragraph:1", NOW);
    }
    private static String sha(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
