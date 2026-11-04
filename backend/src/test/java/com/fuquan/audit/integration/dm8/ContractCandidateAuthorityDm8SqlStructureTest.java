package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractCandidateAuthorityDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__contract_candidate_authority.sql");

    @Test
    void declaresControlledNamespaceAndParseBoundCandidateAuthority() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: contract-candidate-authority",
                "-- DEPENDS-ON: project-file-parse-result,project-file-role-assessment",
                "-- PURPOSE: bind contract candidates and candidate evidence to immutable parse and authoritative role decisions",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");

        String sql = Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(sql).contains(
                "ALTER TABLE CONTRACT_CANDIDATE ADD SOURCE_PARSE_RESULT_ID VARCHAR(36)",
                "ALTER TABLE CONTRACT_CANDIDATE ADD SOURCE_ROLE_DECISION_ID VARCHAR(36)",
                "ALTER TABLE CONTRACT_CANDIDATE ADD SOURCE_AUTHORITY_SOURCE VARCHAR(32)",
                "ALTER TABLE CONTRACT_CANDIDATE ADD SOURCE_FACT_FINGERPRINT VARCHAR(64)",
                "ALTER TABLE PROJECT_EXTRACTION_RESULT ADD PARSE_RESULT_ID VARCHAR(36)",
                "CONSTRAINT FK_CONTRACT_CANDIDATE_SOURCE_PARSE FOREIGN KEY (SOURCE_PARSE_RESULT_ID, PROJECT_ID, SOURCE_CONTRACT_FILE_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "CONSTRAINT FK_CONTRACT_CANDIDATE_SOURCE_ROLE_DECISION FOREIGN KEY (SOURCE_ROLE_DECISION_ID, PROJECT_ID, SOURCE_CONTRACT_FILE_ID, SOURCE_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_ROLE_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "CONSTRAINT FK_PROJECT_EXTRACTION_PARSE_RESULT FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, SOURCE_FILE_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "CK_CONTRACT_CANDIDATE_AUTHORITY_SOURCE",
                "HUMAN_CONFIRMATION", "LEGACY_COMPAT",
                "IDX_CONTRACT_CANDIDATE_SOURCE_PARSE",
                "IDX_CONTRACT_CANDIDATE_SOURCE_ROLE_DECISION",
                "IDX_PROJECT_EXTRACTION_SOURCE_PARSE");
        assertThat(sql).doesNotContain("UPDATE CONTRACT_CANDIDATE SET", "UPDATE PROJECT_EXTRACTION_RESULT SET PARSE_RESULT_ID");
    }

    @Test
    void bindsCandidateAndEvidenceToTheSameProjectFileAndRejectsPartialAuthorityTuples() throws Exception {
        String sql = Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(sql).contains(
                "UNIQUE (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "UNIQUE (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "UNIQUE (PROJECT_ID, ID, SOURCE_CONTRACT_FILE_ID)",
                "UNIQUE (PROJECT_ID, ID, SOURCE_PARSE_RESULT_ID)",
                "FOREIGN KEY (SOURCE_PARSE_RESULT_ID, PROJECT_ID, SOURCE_CONTRACT_FILE_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "FOREIGN KEY (SOURCE_ROLE_DECISION_ID, PROJECT_ID, SOURCE_CONTRACT_FILE_ID, SOURCE_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_ROLE_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, SOURCE_FILE_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "FOREIGN KEY (PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID) REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID, SOURCE_CONTRACT_FILE_ID)",
                "FOREIGN KEY (PROJECT_ID, CANDIDATE_ID, PARSE_RESULT_ID) REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID, SOURCE_PARSE_RESULT_ID)",
                "CK_CONTRACT_CANDIDATE_AUTHORITY_TUPLE",
                "SOURCE_AUTHORITY_SOURCE = 'LEGACY_COMPAT' AND SOURCE_FACT_FINGERPRINT IS NULL");
    }
}
