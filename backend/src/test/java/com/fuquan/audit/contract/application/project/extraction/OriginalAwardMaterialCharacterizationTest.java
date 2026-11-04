package com.fuquan.audit.contract.application.project.extraction;

import static com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest.file;
import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.application.project.ExtractionKeyFactory;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only when the read-only original-material root is supplied explicitly. */
class OriginalAwardMaterialCharacterizationTest {
    @Test
    @EnabledIfSystemProperty(named = "audit.original.materials.root", matches = ".+")
    void originalTextPdfNoticesExposeFinalAwardFactsWithExactFileProvenance() throws Exception {
        Path root = Path.of(System.getProperty("audit.original.materials.root"));
        assertNotice(root, "01_基准一致项目", "07d81f08f86b7ac4190c3012e98fb3dda6f067db84b3319ca54301932ed19a64");
        assertNotice(root, "02_金额冲突项目", "e8a10b7b80b630ca052b77503dbff3a315c846316f06ccda20e747c2d80b2efd");
        assertNotice(root, "03_履约条款冲突项目", "b4623aa54104ab0ec961837de8e2960872ec135eeeac08082fcaa6d511c7ccf4");
        assertNotice(root, "04_违约责任冲突项目", "8887025548bf61b554c62c63b6971f52ff34a1f55dba30259ba9eb21a867f5d1");
        assertNotice(root, "05_混入其他项目", "12f5758232fb126ea0591dbec86010dba2a5ac8c87fa1d1fe50d6a734c4ff3ab");
    }

    @Test
    @EnabledIfSystemProperty(named = "audit.original.materials.root", matches = ".+")
    void originalContractHasFourAmountRowsButNoReliableQuantityAndUnitPricePair() throws Exception {
        Path root = Path.of(System.getProperty("audit.original.materials.root"));
        Path original = root.resolve("01_基准一致项目").resolve("04_智慧维修系统软件采购合同.docx");
        byte[] bytes = Files.readAllBytes(original);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("1d4c66f148d97420e81bbe5804fd4d67167498b1b45442156660515994caef0a");
        ProjectFile source = file(original.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
        var parser = new ProjectDocxTextParser(new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(limits), limits));
        com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument parsed;
        try (InputStream input = Files.newInputStream(original)) {
            parsed = parser.parse(source, input);
        }
        String cells = parsed.tables().stream().flatMap(table -> table.rows().stream())
                .flatMap(row -> row.cells().stream()).map(cell -> cell.text())
                .collect(Collectors.joining(" | "));
        assertThat(cells).contains("28,000", "16,000", "9,000", "14,000");
        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC))
                .extractPotential(source, parsed, UUID.randomUUID());
        assertThat(facts).filteredOn(fact -> fact.factType().startsWith("LINE_ITEM_")).isEmpty();
    }

    private static void assertNotice(Path root, String suite, String expectedSha256) throws Exception {
        Path original = root.resolve(suite).resolve("03_智慧维修系统成交通知书.pdf");
        byte[] bytes = Files.readAllBytes(original);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(expectedSha256);
        ProjectFile fixture = file(original.getFileName().toString(), "application/pdf");
        ProjectFile source = new ProjectFile(fixture.id(), fixture.projectId(), fixture.parentArchiveFileId(),
                fixture.normalizedRelativePath(), fixture.originalFilename(), fixture.mediaType(),
                ProjectFileCategory.AWARD_NOTICE, fixture.ingestionStatus(), fixture.parseStatus(),
                fixture.storageKey(), expectedSha256, bytes.length, fixture.parsedText(),
                fixture.safeReasonCode(), fixture.createdAt(), fixture.updatedAt(), fixture.parseProvenance());
        var parser = new ProjectPdfTextParser();
        com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument parsed;
        try (InputStream input = Files.newInputStream(original)) {
            parsed = parser.parse(source, input);
        }
        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC))
                .extractPotential(source, parsed, UUID.randomUUID());
        String relevantLines = parsed.fullText().lines()
                .filter(line -> line.contains("成交") || line.contains("中标") || line.contains("金额")
                        || line.contains("供应商"))
                .limit(12).collect(Collectors.joining(" | "));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .extracting(ExtractionResult::normalizedValue)
                .withFailMessage("No final award amount in parsed notice lines: %s", relevantLines)
                .contains("67000");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .isNotEmpty().allSatisfy(fact -> {
                    assertThat(fact.sourceFileId()).isEqualTo(source.id());
                    assertThat(fact.sourceLocator()).startsWith("pdf/page:");
                });
    }
}
