package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F07TenderRuleSqlStructureTest {
    private final Path migration = Path.of("..", "db", "dm8", "V036__f07_tender_rule_scan.sql");
    private final Path expansion = Path.of("..", "db", "dm8", "V037__f07_tender_rule_catalog_expansion.sql");
    private final Path regexFix = Path.of("..", "db", "dm8", "V038__f07_fix_tender_rule_regex_boundaries.sql");
    private final Path aiMetadata = Path.of("..", "db", "dm8", "V039__f07_tender_ai_finding_metadata.sql");
    private final Path legalMetadata = Path.of("..", "db", "dm8", "V040__f07_tender_rule_legal_metadata.sql");

    @Test
    void migrationDefinesRuleAndFindingTablesAndSeedRules() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();
        assertThat(sql).contains(
                "CREATE TABLE PROCUREMENT_TENDER_RULE",
                "CREATE TABLE PROCUREMENT_TENDER_FINDING",
                "RULE_CODE",
                "MATCH_TYPE",
                "MATCH_CONTENT",
                "SOURCE_LOCATOR",
                "F07-BRAND-001",
                "F07-REGION-001",
                "F07-EXCLUSIVE-001",
                "F07-QUALIFICATION-001",
                "F07-TECHNICAL-001",
                "COMMIT");
        assertThat(sql).doesNotContain(" BOOLEAN", " JSON", " LIMIT ");
    }

    @Test
    void expansionAddsRuleSourceAndAllRuleFamilies() throws Exception {
        String sql = Files.readString(expansion, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "ADD SOURCE VARCHAR(16) DEFAULT 'BUILTIN' NOT NULL",
                "CK_PROC_TENDER_RULE_SOURCE",
                "'BUILTIN'",
                "F07-BRAND-008",
                "F07-REGION-008",
                "F07-EXCLUSIVE-008",
                "F07-QUAL-008",
                "F07-TECH-008",
                "F07-SCORE-006",
                "F07-PAYMENT-006",
                "F07-DURATION-005",
                "F07-SERVICE-005");
        assertThat(sql).doesNotContain("BOOLEAN", " JSON", " LIMIT ");
    }

    @Test
    void regexFixMigrationStoresSafePunctuationBoundaries() throws Exception {
        String sql = Files.readString(regexFix, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "F07-BRAND-002", "F07-BRAND-003", "F07-BRAND-007", "F07-REGION-008",
                "F07-QUAL-001", "F07-QUAL-008", "F07-TECH-001", "F07-TECH-008",
                "F07-SCORE-001", "F07-SCORE-006", "F07-PAYMENT-001", "F07-PAYMENT-004",
                "F07-DURATION-001", "F07-DURATION-003", "F07-SERVICE-002", "F07-SERVICE-003",
                "[^，。；：！？,.!?]", "COMMIT");
        assertThat(sql).doesNotContain("\\R", "\\N");
    }

    @Test
    void aiMetadataMigrationAddsFindingSourceAndReason() throws Exception {
        String sql = Files.readString(aiMetadata, StandardCharsets.UTF_8).toUpperCase();
        assertThat(sql).contains(
                "ADD SOURCE VARCHAR(16)",
                "ADD RISK_REASON VARCHAR(2000)",
                "RULE_AND_AI",
                "CK_PROC_TENDER_FINDING_SOURCE",
                "COMMIT");
    }

    @Test
    void legalMetadataMigrationAddsBasisAndExplanationForAllRuleFamilies() throws Exception {
        String sql = Files.readString(legalMetadata, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "ADD LEGAL_BASIS VARCHAR(1000)",
                "ADD RULE_EXPLANATION VARCHAR(2000)",
                "LEGAL_BASIS",
                "RULE_EXPLANATION",
                "指定品牌", "地域限制", "排他性条件", "资质门槛", "技术参数倾向",
                "评分标准倾向", "付款条件", "工期要求", "售后服务",
                "政府采购法实施条例", "COMMIT");
        assertThat(sql).doesNotContain("DROP TABLE", "DELETE FROM PROCUREMENT_TENDER_RULE");
    }
}
