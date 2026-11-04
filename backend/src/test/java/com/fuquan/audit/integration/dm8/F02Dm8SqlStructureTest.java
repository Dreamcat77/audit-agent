package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class F02Dm8SqlStructureTest {

    @Test
    void taskTransitionIsConditionalOnStatusAndVersion() throws Exception {
        String xml = resource("/mappers/dm8/AuditTaskMapper.xml");

        assertThat(xml).contains("STATUS = #{expectedStatus}")
                .contains("VERSION_NO = #{expectedVersion}")
                .contains("VERSION_NO = VERSION_NO + 1")
                .doesNotContain("countById");
    }

    @Test
    void idempotencyClaimsByInsertAndUniqueConstraint() throws Exception {
        String xml = resource("/mappers/dm8/IdempotencyMapper.xml");
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V003__f02_contract_review.sql"), StandardCharsets.UTF_8);

        assertThat(xml).contains("INSERT INTO IDEMPOTENCY_RECORD")
                .doesNotContain("DELETE FROM IDEMPOTENCY_RECORD");
        assertThat(migration).contains("UNIQUE (SCOPE, IDEMPOTENCY_KEY)")
                .contains("CREATE TABLE AUDIT_CHECK_RESULT")
                .contains("DECIMAL(18,2)")
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain("MERGE INTO");
    }

    @Test
    void ruleQueryMatchesTheLiteralF02UnderscorePrefix() throws Exception {
        String xml = resource("/mappers/dm8/ContractRuleConfigMapper.xml");

        assertThat(xml)
                .contains("SUBSTR(RULE_CODE, 1, 4) = 'F02_'")
                .doesNotContain("LIKE 'F02_%'");
    }

    @Test
    void failedIdempotencyUpdateDoesNotClearClaimedTaskIdentity() throws Exception {
        String xml = resource("/mappers/dm8/IdempotencyMapper.xml");
        String failedUpdate = xml.substring(xml.indexOf("<update id=\"markFailed\">"));
        failedUpdate = failedUpdate.substring(0, failedUpdate.indexOf("</update>"));

        assertThat(failedUpdate)
                .contains("SET STATUS = 'FAILED'")
                .doesNotContain("RESULT_RESOURCE_TYPE")
                .doesNotContain("RESULT_RESOURCE_ID");
    }

    @Test
    void demoSeedIsExplicitlyNonProductionAndInsertOnly() throws Exception {
        String seed = Files.readString(
                Path.of("..", "db", "dm8", "V003_f02_seed.sql"), StandardCharsets.UTF_8);
        Pattern destructiveStatement = Pattern.compile(
                "^\\s*(DELETE|UPDATE|MERGE|TRUNCATE)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        assertThat(seed)
                .contains("TEST/DEMO SEED ONLY")
                .contains("禁止在生产环境执行")
                .contains("INSERT INTO CONTRACT")
                .contains("INSERT INTO CONTRACT_LINE_ITEM")
                .contains("INSERT INTO CONTRACT_AWARD_NOTICE");
        assertThat(destructiveStatement.matcher(seed).find()).isFalse();
    }

    @Test
    void customerCaseSeedIsNonMigrationInsertOnlyAndDoesNotBypassReviewSessionApis() throws Exception {
        String seed = Files.readString(
                Path.of("..", "db", "dm8", "demo", "contract_f02_customer_cases_20260821.sql"),
                StandardCharsets.UTF_8);
        Pattern destructiveStatement = Pattern.compile(
                "^\\s*(DELETE|UPDATE|MERGE|TRUNCATE|DROP)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Pattern contractInsert = Pattern.compile(
                "INSERT\\s+INTO\\s+CONTRACT\\s*\\(", Pattern.CASE_INSENSITIVE);
        Pattern reviewSessionInsert = Pattern.compile(
                "INSERT\\s+INTO\\s+CONTRACT_REVIEW_(SESSION|DOCUMENT|MATERIAL_BINDING)\\b",
                Pattern.CASE_INSENSITIVE);

        assertThat(seed)
                .contains("TEST / DEMO DATA ONLY")
                .contains("NOT MIGRATION")
                .contains("CT-2026-0101")
                .contains("CT-2026-0102")
                .contains("CT-2026-0103")
                .contains("CT-2026-0104")
                .contains("INSERT INTO CONTRACT_LINE_ITEM")
                .contains("INSERT INTO CONTRACT_AWARD_NOTICE");
        assertThat(contractInsert.matcher(seed).results()).hasSize(4);
        assertThat(destructiveStatement.matcher(seed).find()).isFalse();
        assertThat(reviewSessionInsert.matcher(seed).find()).isFalse();
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
