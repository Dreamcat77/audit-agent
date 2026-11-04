package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractReviewRunDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__review_run.sql");

    @Test
    void controlledMigrationCreatesOnlyRunMaterialsStepsAndAttempts() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: review-run",
                "-- DEPENDS-ON: contract-candidate-confirmation",
                "-- PURPOSE: durable immutable contract review runs, material snapshots and step manifests",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = normalized();
        assertThat(sql).contains("CREATE TABLE CONTRACT_REVIEW_RUN (",
                "CREATE TABLE CONTRACT_REVIEW_RUN_MATERIAL (",
                "CREATE TABLE CONTRACT_REVIEW_STEP (",
                "CREATE TABLE CONTRACT_REVIEW_STEP_ATTEMPT (");
        assertThat(sql.split("CREATE TABLE ", -1)).hasSize(5);
        assertThat(sql).contains("CONTRACT_SNAPSHOT_CANONICAL_JSON CLOB NOT NULL",
                "CONTRACT_SNAPSHOT_SHA256 VARCHAR(64) NOT NULL",
                "INPUT_SNAPSHOT_IDENTITY VARCHAR(64) NOT NULL",
                "SOURCE_CONFIRMATION_ID VARCHAR(36)", "IDENTITY_DECISION_ID VARCHAR(36) NOT NULL",
                "ROLE_DECISION_ID VARCHAR(36)", "OPERATION_IDENTITY VARCHAR(64) NOT NULL",
                "EXTERNAL_CALL_STATE VARCHAR(32) NOT NULL", "LEASE_OWNER VARCHAR(100)");
        assertThat(sql).contains("SOURCE_CANDIDATE_ID VARCHAR(36),");
        assertThat(sql).doesNotContain("SOURCE_CANDIDATE_ID VARCHAR(36) NOT NULL");
        assertThat(sql).doesNotContain("ALTER TABLE PROJECT ", "ALTER TABLE CONTRACT_CANDIDATE ",
                "CREATE TABLE CONTRACT_REVIEW_FINDING", "CREATE TABLE CONTRACT_REVIEW_REPORT", "V023", "V024");
    }

    @Test
    void ownershipBindingsAndUniquenessAreDatabaseDefenses() throws Exception {
        String sql = normalized();
        assertThat(sql).contains(
                "ALTER TABLE PROJECT_CONTRACT ADD CONSTRAINT UK_PROJECT_CONTRACT_RUN_SOURCE UNIQUE (PROJECT_ID, CONTRACT_ID, CANDIDATE_ID)",
                "FOREIGN KEY (PROJECT_ID, CONTRACT_ID) REFERENCES PROJECT_CONTRACT (PROJECT_ID, CONTRACT_ID)",
                "FOREIGN KEY (PROJECT_ID, CONTRACT_ID, SOURCE_CANDIDATE_ID) REFERENCES PROJECT_CONTRACT (PROJECT_ID, CONTRACT_ID, CANDIDATE_ID)",
                "FOREIGN KEY (PROJECT_ID, SOURCE_CANDIDATE_ID) REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID)",
                "FOREIGN KEY (SOURCE_CONFIRMATION_ID, PROJECT_ID, SOURCE_CANDIDATE_ID) REFERENCES CONTRACT_CANDIDATE_CONFIRMATION (CONFIRMATION_ID, PROJECT_ID, CANDIDATE_ID)",
                "FOREIGN KEY (RUN_ID, PROJECT_ID) REFERENCES CONTRACT_REVIEW_RUN (RUN_ID, PROJECT_ID)",
                "FOREIGN KEY (PROJECT_ID, FILE_ID) REFERENCES PROJECT_FILE (PROJECT_ID, ID)",
                "FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "FOREIGN KEY (ROLE_DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID) REFERENCES PROJECT_FILE_ROLE_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "FOREIGN KEY (IDENTITY_DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID) REFERENCES PROJECT_FILE_IDENTITY_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "UNIQUE (RUN_ID, FILE_ID)", "UNIQUE (RUN_ID, ORDINAL)",
                "UNIQUE (RUN_ID, STEP_TYPE)", "UNIQUE (STEP_ID, ATTEMPT_NO)",
                "UNIQUE (OPERATION_IDENTITY)");
    }

    @Test
    void runClobCannotForceAnImplicitClusteredPrimaryKeyOnDm8() throws Exception {
        assertThat(normalized()).contains(
                "CONSTRAINT PK_CONTRACT_REVIEW_RUN NOT CLUSTER PRIMARY KEY (RUN_ID)");
    }

    private static String normalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
