package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractCandidateSupportAuthorityDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__contract_candidate_support_authority.sql");

    @Test
    void declaresControlledNamespaceAndNullableCandidateSupportCohortMetadata() throws Exception {
        assertThat(Files.exists(SQL)).as("controlled support-authority migration").isTrue();
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: contract-candidate-support-authority",
                "-- DEPENDS-ON: project-file-identity-assessment,contract-candidate-authority",
                "-- PURPOSE: bind candidate award support to exact current file parse role identity and fact authority",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");

        String sql = normalized();
        assertThat(sql).contains(
                "ALTER TABLE CONTRACT_CANDIDATE ADD SUPPORT_AUTHORITY_VERSION_NO BIGINT",
                "ALTER TABLE CONTRACT_CANDIDATE ADD SUPPORT_AUTHORITY_FINGERPRINT VARCHAR(64)",
                "UNIQUE (PROJECT_ID, ID, SUPPORT_AUTHORITY_VERSION_NO)",
                "CK_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_TUPLE",
                "SUPPORT_AUTHORITY_VERSION_NO IS NULL AND SUPPORT_AUTHORITY_FINGERPRINT IS NULL",
                "SUPPORT_AUTHORITY_VERSION_NO IS NOT NULL AND SUPPORT_AUTHORITY_FINGERPRINT IS NOT NULL",
                "CK_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_VERSION",
                "SUPPORT_AUTHORITY_VERSION_NO > 0",
                "CK_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_FINGERPRINT",
                "REGEXP_LIKE(SUPPORT_AUTHORITY_FINGERPRINT, '^[0-9A-F]{64}$')");
        assertThat(casePreservingNormalized()).contains(
                "SUPPORT_AUTHORITY_FINGERPRINT = LOWER(SUPPORT_AUTHORITY_FINGERPRINT)",
                "REGEXP_LIKE(SUPPORT_AUTHORITY_FINGERPRINT, '^[0-9a-f]{64}$')");
    }

    @Test
    void createsCurrentCandidateOwnedSupportRowsWithExactAuthorityBindings() throws Exception {
        assertThat(Files.exists(SQL)).as("controlled support-authority migration").isTrue();
        String sql = normalized();
        assertThat(sql).contains(
                "CREATE TABLE CONTRACT_CANDIDATE_SUPPORT_AUTHORITY",
                "SUPPORT_AUTHORITY_ID VARCHAR(36) NOT NULL",
                "PROJECT_ID VARCHAR(36) NOT NULL", "CANDIDATE_ID VARCHAR(36) NOT NULL",
                "AUTHORITY_VERSION_NO BIGINT NOT NULL", "SOURCE_FILE_ID VARCHAR(36) NOT NULL",
                "FILE_SHA256 VARCHAR(64) NOT NULL", "PARSE_RESULT_ID VARCHAR(36) NOT NULL",
                "PARSE_VERSION INTEGER NOT NULL", "PROJECT_VERSION_NO BIGINT NOT NULL",
                "EFFECTIVE_ROLE VARCHAR(32) NOT NULL", "ROLE_DECISION_ID VARCHAR(36) NOT NULL",
                "IDENTITY_RESOLUTION VARCHAR(40) NOT NULL", "IDENTITY_DECISION_ID VARCHAR(36) NOT NULL",
                "SOURCE_FACT_FINGERPRINT VARCHAR(64) NOT NULL", "CREATED_BY VARCHAR(36) NOT NULL",
                "CREATED_AT TIMESTAMP NOT NULL",
                "PRIMARY KEY (SUPPORT_AUTHORITY_ID)",
                "FOREIGN KEY (PROJECT_ID, CANDIDATE_ID, AUTHORITY_VERSION_NO) REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID, SUPPORT_AUTHORITY_VERSION_NO)",
                "FOREIGN KEY (PROJECT_ID, SOURCE_FILE_ID) REFERENCES PROJECT_FILE (PROJECT_ID, ID)",
                "FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_VERSION) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "FOREIGN KEY (ROLE_DECISION_ID, PROJECT_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, EFFECTIVE_ROLE) REFERENCES PROJECT_FILE_ROLE_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, SELECTED_ROLE)",
                "FOREIGN KEY (IDENTITY_DECISION_ID, PROJECT_ID, PROJECT_VERSION_NO, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, IDENTITY_RESOLUTION) REFERENCES PROJECT_FILE_IDENTITY_DECISION (DECISION_ID, PROJECT_ID, PROJECT_VERSION_NO, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, RESOLUTION)",
                "FOREIGN KEY (CREATED_BY) REFERENCES SYS_USER (ID)",
                "EFFECTIVE_ROLE = 'AWARD_NOTICE'", "IDENTITY_RESOLUTION = 'CONFIRM_SAME_PROJECT'",
                "PARSE_VERSION > 0 AND AUTHORITY_VERSION_NO > 0 AND PROJECT_VERSION_NO > 0",
                "REGEXP_LIKE(FILE_SHA256, '^[0-9A-F]{64}$')",
                "REGEXP_LIKE(SOURCE_FACT_FINGERPRINT, '^[0-9A-F]{64}$')",
                "UNIQUE (PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID)");
        assertThat(casePreservingNormalized()).contains(
                "FILE_SHA256 = LOWER(FILE_SHA256)",
                "REGEXP_LIKE(FILE_SHA256, '^[0-9a-f]{64}$')",
                "SOURCE_FACT_FINGERPRINT = LOWER(SOURCE_FACT_FINGERPRINT)",
                "REGEXP_LIKE(SOURCE_FACT_FINGERPRINT, '^[0-9a-f]{64}$')");
        assertThat(sql).doesNotContain(" ACTIVE", "ROW_VERSION", "UPDATED_AT");
    }

    @Test
    void addsRequiredExactParentKeysAndLookupIndexesWithoutWeakeningExistingOwnership() throws Exception {
        assertThat(Files.exists(SQL)).as("controlled support-authority migration").isTrue();
        String sql = normalized();
        assertThat(sql).contains(
                "UNIQUE (DECISION_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, SELECTED_ROLE)",
                "UNIQUE (DECISION_ID, PROJECT_ID, PROJECT_VERSION_NO, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, RESOLUTION)",
                "IDX_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_CURRENT",
                "ON CONTRACT_CANDIDATE_SUPPORT_AUTHORITY (PROJECT_ID, CANDIDATE_ID, AUTHORITY_VERSION_NO, SOURCE_FILE_ID)",
                "IDX_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_SOURCE",
                "ON CONTRACT_CANDIDATE_SUPPORT_AUTHORITY (PROJECT_ID, SOURCE_FILE_ID, CANDIDATE_ID)",
                "IDX_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_ROLE_DECISION",
                "ON CONTRACT_CANDIDATE_SUPPORT_AUTHORITY (ROLE_DECISION_ID, PROJECT_ID, SOURCE_FILE_ID, PARSE_RESULT_ID, PARSE_VERSION)",
                "IDX_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_IDENTITY_DECISION",
                "ON CONTRACT_CANDIDATE_SUPPORT_AUTHORITY (IDENTITY_DECISION_ID, PROJECT_ID, SOURCE_FILE_ID, PARSE_RESULT_ID, PARSE_VERSION)",
                "IDX_CONTRACT_CANDIDATE_SUPPORT_AUTHORITY_PARSE_EVIDENCE",
                "ON CONTRACT_CANDIDATE_SUPPORT_AUTHORITY (PROJECT_ID, SOURCE_FILE_ID, PARSE_RESULT_ID)");
        assertThat(sql).doesNotContain("DROP CONSTRAINT", "DROP TABLE", "UPDATE CONTRACT_CANDIDATE SET",
                "UPDATE PROJECT_EXTRACTION_RESULT SET", "DROP CONSTRAINT FK_PROJECT_EXTRACTION_CANDIDATE_SOURCE",
                "DROP CONSTRAINT FK_PROJECT_EXTRACTION_CANDIDATE_PARSE", "V023", "BACKFILL", "STARTUP MIGRATION");
    }

    private static String normalized() throws Exception {
        return casePreservingNormalized().toUpperCase(Locale.ROOT);
    }

    private static String casePreservingNormalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ");
    }
}
