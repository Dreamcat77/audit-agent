package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractFactV2AuditDm8SqlStructureTest {
    @Test
    void migrationWidensWithoutLosingIntegerCapacityAndAddsOwnedSafeAuditRows() throws Exception {
        String sql = Files.readString(Path.of("..", "db", "dm8",
                "CONTRACT__contract_fact_v2_audit.sql"));
        String normalized = sql.replaceAll("\\s+", " ");

        assertThat(sql).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-fact-v2-audit",
                        "-- EXECUTION: CONTROLLED_MANUAL",
                        "ALTER TABLE PROJECT_EXTRACTION_RESULT MODIFY VALUE_AMOUNT DECIMAL(20,4)",
                        "ALTER TABLE CONTRACT_CANDIDATE_LINE_ITEM MODIFY UNIT_PRICE DECIMAL(20,4)",
                        "CREATE TABLE PROJECT_FACT_EXTRACTION_REJECTION",
                        "ATTEMPT_NO INT NOT NULL",
                        "PROVIDER_ORDINAL INT NOT NULL",
                        "SAFE_SHAPE_HASH VARCHAR(64) NOT NULL",
                        "CREATE TABLE CONTRACT_MODEL_CALL_AUDIT",
                        "FACT_EXTRACTION_ATTEMPT_ID VARCHAR(36)",
                        "REVIEW_STEP_ATTEMPT_ID VARCHAR(36)",
                        "REQUEST_FINGERPRINT VARCHAR(64) NOT NULL",
                        "BATCH_STATUS VARCHAR(32)",
                        "PROMPT_TOKENS INT",
                        "COMPLETION_TOKENS INT",
                        "TOTAL_TOKENS INT")
                .doesNotContain("DROP TABLE", "TRUNCATE", "DELETE FROM", "DECIMAL(18,4)");
        assertThat(normalized).contains(
                "UNIQUE (ATTEMPT_ID, ATTEMPT_NO, SCOPE, PROVIDER_ORDINAL)",
                "UNIQUE (FACT_EXTRACTION_ATTEMPT_ID, ATTEMPT_NO, SCOPE, CALL_ORDINAL)",
                "UNIQUE (REVIEW_STEP_ATTEMPT_ID, ATTEMPT_NO, CALL_ORDINAL)",
                "FOREIGN KEY (ATTEMPT_ID) REFERENCES PROJECT_FACT_EXTRACTION_ATTEMPT (ATTEMPT_ID)",
                "FOREIGN KEY (FACT_EXTRACTION_ATTEMPT_ID) REFERENCES PROJECT_FACT_EXTRACTION_ATTEMPT (ATTEMPT_ID)",
                "FOREIGN KEY (REVIEW_STEP_ATTEMPT_ID) REFERENCES CONTRACT_REVIEW_STEP_ATTEMPT (ATTEMPT_ID)",
                "(FACT_EXTRACTION_ATTEMPT_ID IS NOT NULL AND REVIEW_STEP_ATTEMPT_ID IS NULL)",
                "(FACT_EXTRACTION_ATTEMPT_ID IS NULL AND REVIEW_STEP_ATTEMPT_ID IS NOT NULL)");
    }
}
