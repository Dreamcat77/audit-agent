package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractCandidateConfirmationDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__contract_candidate_confirmation.sql");

    @Test
    void controlledAdditiveMigrationDeclaresImmutableSnapshotAndPromotionProvenance() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: contract-candidate-confirmation",
                "-- DEPENDS-ON: project-file-identity-assessment",
                "-- PURPOSE: immutable human confirmation snapshots for contract candidate promotion",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = normalized();
        assertThat(sql).contains("CREATE TABLE CONTRACT_CANDIDATE_CONFIRMATION",
                "CANONICAL_PAYLOAD_JSON CLOB NOT NULL", "PAYLOAD_FINGERPRINT VARCHAR(64) NOT NULL",
                "BASE_CANDIDATE_VERSION_NO BIGINT NOT NULL", "RESULT_CANDIDATE_VERSION_NO BIGINT NOT NULL",
                "SOURCE_FILE_ID VARCHAR(36) NOT NULL", "SOURCE_PARSE_RESULT_ID VARCHAR(36) NOT NULL",
                "SOURCE_ROLE_DECISION_ID VARCHAR(36) NOT NULL",
                "SOURCE_IDENTITY_DECISION_ID VARCHAR(36) NOT NULL",
                "SUPERSEDES_CONFIRMATION_ID VARCHAR(36)",
                "ALTER TABLE CONTRACT_CANDIDATE ADD PROMOTED_CONFIRMATION_ID VARCHAR(36)");
        assertThat(sql).doesNotContain("UPDATE CONTRACT_CANDIDATE SET", "V023", "V024");
    }

    @Test
    void strongOwnerBindingsAndVersionUniquenessPreventCrossCandidateSnapshots() throws Exception {
        String sql = normalized();
        assertThat(sql).contains(
                "UNIQUE (CANDIDATE_ID, RESULT_CANDIDATE_VERSION_NO)",
                "FOREIGN KEY (PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID) REFERENCES CONTRACT_CANDIDATE (PROJECT_ID, ID, SOURCE_CONTRACT_FILE_ID)",
                "FOREIGN KEY (SOURCE_PARSE_RESULT_ID, PROJECT_ID, SOURCE_FILE_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID)",
                "FOREIGN KEY (SOURCE_ROLE_DECISION_ID, PROJECT_ID, SOURCE_FILE_ID, SOURCE_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_ROLE_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "FOREIGN KEY (SOURCE_IDENTITY_DECISION_ID, PROJECT_ID, SOURCE_FILE_ID, SOURCE_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_IDENTITY_DECISION (DECISION_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID)",
                "FOREIGN KEY (CONFIRMED_BY) REFERENCES SYS_USER (ID)",
                "FOREIGN KEY (SUPERSEDES_CONFIRMATION_ID, PROJECT_ID, CANDIDATE_ID) REFERENCES CONTRACT_CANDIDATE_CONFIRMATION (CONFIRMATION_ID, PROJECT_ID, CANDIDATE_ID)",
                "FOREIGN KEY (PROMOTED_CONFIRMATION_ID, PROJECT_ID, ID) REFERENCES CONTRACT_CANDIDATE_CONFIRMATION (CONFIRMATION_ID, PROJECT_ID, CANDIDATE_ID)");
    }

    @Test
    void candidateMapperHasConfirmationAndPromotionCas() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractCandidateMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(xml).contains("PROMOTED_CONFIRMATION_ID", "ID=#{CANDIDATEID}",
                "PROMOTION_STATE='DRAFT' AND VERSION_NO=#{EXPECTEDVERSION}",
                "VERSION_NO=VERSION_NO+1");
        String confirmationXml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractCandidateConfirmationMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(confirmationXml).contains("INSERT INTO CONTRACT_CANDIDATE_CONFIRMATION",
                "CANONICAL_PAYLOAD_JSON", "ORDER BY RESULT_CANDIDATE_VERSION_NO DESC");
    }

    private static String normalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
