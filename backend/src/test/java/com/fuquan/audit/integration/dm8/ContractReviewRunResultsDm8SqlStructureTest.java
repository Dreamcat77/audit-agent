package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractReviewRunResultsDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__review_run_results.sql");

    @Test
    void controlledMigrationAddsOnlyFindingAndEvidenceTables() throws Exception {
        assertThat(Files.exists(SQL)).isTrue();
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: review-run-results",
                "-- DEPENDS-ON: review-run",
                "-- PURPOSE: append-only review-run findings and parse-bound evidence",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = normalized();
        assertThat(sql).contains("CREATE TABLE CONTRACT_REVIEW_FINDING (",
                "CREATE TABLE CONTRACT_REVIEW_EVIDENCE (");
        assertThat(sql.split("CREATE TABLE ", -1)).hasSize(3);
        assertThat(sql).doesNotContain("CREATE TABLE CONTRACT_REVIEW_REPORT",
                "CREATE TABLE CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT",
                "RAW_MODEL_BODY", "RAW_PROMPT", "CHAIN_OF_THOUGHT");
    }

    @Test
    void compositeKeysBindFindingToAttemptAndEvidenceToFindingAndFrozenMaterial() throws Exception {
        assertThat(Files.exists(SQL)).isTrue();
        String sql = normalized();
        assertThat(sql).contains(
                "ALTER TABLE CONTRACT_REVIEW_STEP_ATTEMPT ADD CONSTRAINT UK_CONTRACT_REVIEW_STEP_ATTEMPT_OWNER UNIQUE (RUN_ID, STEP_ID, ATTEMPT_ID)",
                "ALTER TABLE CONTRACT_REVIEW_RUN_MATERIAL ADD CONSTRAINT UK_CONTRACT_REVIEW_RUN_MATERIAL_PARSE_TUPLE UNIQUE (RUN_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, CANONICAL_TEXT_SHA256)",
                "FOREIGN KEY (RUN_ID, STEP_ID, ATTEMPT_ID) REFERENCES CONTRACT_REVIEW_STEP_ATTEMPT (RUN_ID, STEP_ID, ATTEMPT_ID)",
                "FOREIGN KEY (FINDING_ID, RUN_ID, STEP_ID, ATTEMPT_ID) REFERENCES CONTRACT_REVIEW_FINDING (FINDING_ID, RUN_ID, STEP_ID, ATTEMPT_ID)",
                "FOREIGN KEY (RUN_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, CANONICAL_TEXT_SHA256) REFERENCES CONTRACT_REVIEW_RUN_MATERIAL (RUN_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, CANONICAL_TEXT_SHA256)",
                "UNIQUE (ATTEMPT_ID, ORDINAL)", "UNIQUE (FINDING_ID, ORDINAL)");
    }

    @Test
    void boundedTypedRowsEnforceHashLocatorAndVersionConstraints() throws Exception {
        assertThat(Files.exists(SQL)).isTrue();
        String sql = normalized();
        assertThat(sql).contains(
                "FINDING_TYPE VARCHAR(128) NOT NULL", "CATEGORY VARCHAR(128) NOT NULL",
                "STATUS_CODE VARCHAR(128)", "ATTENTION_CODE VARCHAR(128)",
                "SUMMARY VARCHAR(1000) NOT NULL", "RECOMMENDATION VARCHAR(2000)",
                "ALTER TABLE CONTRACT_REVIEW_STEP_ATTEMPT MODIFY \"MODEL\" VARCHAR(128)",
                "MODEL_NAME VARCHAR(128)",
                "SOURCE VARCHAR(32) NOT NULL", "SOURCE IN ('DETERMINISTIC', 'MODEL_ASSISTED')",
                "FILE_SHA256 VARCHAR(64) NOT NULL", "CANONICAL_TEXT_SHA256 VARCHAR(64) NOT NULL",
                "EXCERPT_SHA256 VARCHAR(64)", "BOUNDED_EXCERPT VARCHAR(1000)",
                "LOCATOR_AVAILABLE INTEGER NOT NULL", "LOCATOR_AVAILABLE IN (0, 1)",
                "START_OFFSET INTEGER", "END_OFFSET INTEGER",
                "ORDINAL > 0", "PARSE_VERSION > 0", "FINDING_SCHEMA_VERSION > 0",
                "LOCATOR_SCHEMA_VERSION > 0", "VERSION_NO >= 0",
                "LENGTH(FILE_SHA256) = 64", "LENGTH(CANONICAL_TEXT_SHA256) = 64",
                "START_OFFSET IS NULL AND END_OFFSET IS NULL",
                "START_OFFSET >= 0 AND END_OFFSET > START_OFFSET");
        assertThat(sql).doesNotContain("STATUS_CODE VARCHAR(128) NOT NULL",
                "START_OFFSET IS NULL AND END_OFFSET IS NULL AND EXCERPT_SHA256 IS NULL",
                "ALTER TABLE CONTRACT_REVIEW_STEP_ATTEMPT MODIFY MODEL VARCHAR(128)");
    }

    private static String normalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
