package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractProjectFileIdentityDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__project_file_identity_assessment.sql");

    @Test
    void declaresControlledNamespaceAndThreeDurableIdentityTables() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: project-file-identity-assessment",
                "-- DEPENDS-ON: project-file-parse-result,project-file-role-assessment,contract-candidate-authority",
                "-- PURPOSE: durable file-to-project identity assessment and human resolution",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = normalized();
        assertThat(sql).contains("CREATE TABLE PROJECT_FILE_IDENTITY_ASSESSMENT",
                "CREATE TABLE PROJECT_FILE_IDENTITY_EVIDENCE", "CREATE TABLE PROJECT_FILE_IDENTITY_DECISION",
                "PROJECT_VERSION_NO BIGINT NOT NULL", "NORMALIZED_INPUT_FINGERPRINT VARCHAR(64) NOT NULL",
                "COMPARISON_OUTCOME VARCHAR(32) NOT NULL", "EXTRACTION_RESULT_ID VARCHAR(36)",
                "SOURCE_LOCATOR VARCHAR(500)", "LEFT_BOUNDED_VALUE VARCHAR(160)",
                "RIGHT_BOUNDED_VALUE VARCHAR(160)",
                "ALTER TABLE CONTRACT_CANDIDATE ADD SOURCE_IDENTITY_DECISION_ID VARCHAR(36)");
        assertThat(sql).doesNotContain("UPDATE CONTRACT_CANDIDATE SET", "V023", "V024");
    }

    @Test
    void enforcesOwnedParseAssessmentEvidenceDecisionAndActorBindings() throws Exception {
        String sql = normalized();
        assertThat(sql).contains(
                "FOREIGN KEY (PROJECT_ID, FILE_ID) REFERENCES PROJECT_FILE (PROJECT_ID, ID)",
                "FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "FOREIGN KEY (ASSESSMENT_ID, PROJECT_ID, PROJECT_VERSION_NO, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION) REFERENCES PROJECT_FILE_IDENTITY_ASSESSMENT (ASSESSMENT_ID, PROJECT_ID, PROJECT_VERSION_NO, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION)",
                "DECIDED_BY VARCHAR(36) NOT NULL",
                "FOREIGN KEY (DECIDED_BY) REFERENCES SYS_USER (ID)",
                "FOREIGN KEY (SOURCE_IDENTITY_DECISION_ID, PROJECT_ID, SOURCE_CONTRACT_FILE_ID, SOURCE_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_IDENTITY_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "IDX_CONTRACT_CANDIDATE_SOURCE_IDENTITY_DECISION");
    }

    @Test
    void candidateMapperPersistsIdentityProvenanceAndGuardsDraftCas() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractCandidateMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(mapper).contains("SOURCE_IDENTITY_DECISION_ID",
                "#{ROW.SOURCEIDENTITYDECISIONID}",
                "SOURCE_IDENTITY_DECISION_ID=#{IDENTITYDECISIONID}",
                "PROMOTION_STATE='DRAFT' AND VERSION_NO=#{EXPECTEDVERSION}");
    }

    private static String normalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
