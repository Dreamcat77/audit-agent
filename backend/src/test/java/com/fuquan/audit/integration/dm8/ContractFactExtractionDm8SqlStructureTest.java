package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractFactExtractionDm8SqlStructureTest {
    @Test
    void migrationPersistsImmutableInputProvenanceRawDecisionsAndPublicationIdentity() throws Exception {
        String sql = Files.readString(Path.of("..", "db", "dm8",
                "CONTRACT__contract_final_closeout_fact_extraction.sql"));
        String normalizedSql = sql.replaceAll("\\s+", " ");

        assertThat(sql).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-final-closeout-fact-extraction",
                        "-- EXECUTION: CONTROLLED_MANUAL",
                        "CREATE TABLE PROJECT_FACT_EXTRACTION_ATTEMPT",
                        "INPUT_FINGERPRINT VARCHAR(64) NOT NULL",
                        "MODEL_NAME VARCHAR(200) NOT NULL",
                        "UNIQUE (ACTOR_ID, OPERATION_KEY)",
                        "ON PROJECT_FACT_EXTRACTION_ATTEMPT (INPUT_FINGERPRINT)",
                        "CREATE TABLE PROJECT_FACT_EXTRACTION_CANDIDATE",
                        "VALIDATION_REASON VARCHAR(64) NOT NULL",
                        "PUBLISHED_EXTRACTION_ID VARCHAR(36)",
                        "REFERENCES PROJECT_FILE_PARSE_RESULT",
                        "REFERENCES PROJECT_EXTRACTION_RESULT (ID)",
                        "VALIDATION_STATUS='ACCEPTED' AND PUBLISHED_EXTRACTION_ID IS NOT NULL")
                .doesNotContain("DROP TABLE", "TRUNCATE", "V023__");
        assertThat(normalizedSql).contains(
                "FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "FOREIGN KEY (ROLE_DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "FOREIGN KEY (IDENTITY_DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)");
    }
}
