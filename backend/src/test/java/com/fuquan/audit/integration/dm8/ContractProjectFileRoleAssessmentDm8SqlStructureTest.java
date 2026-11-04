package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractProjectFileRoleAssessmentDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__project_file_role_assessment.sql");

    @Test
    void declaresControlledContractDependencyAndExactlyThreeRoleHistoryTables() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: project-file-role-assessment",
                "-- DEPENDS-ON: project-file-parse-result",
                "-- PURPOSE: durable machine role assessment, evidence, and human decision history",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = normalized();
        assertThat(count(sql, "CREATE TABLE ")).isEqualTo(3);
        assertThat(sql).contains("CREATE TABLE PROJECT_FILE_ROLE_ASSESSMENT",
                "CREATE TABLE PROJECT_FILE_ROLE_EVIDENCE", "CREATE TABLE PROJECT_FILE_ROLE_DECISION",
                "CANDIDATE_ROLES_JSON CLOB", "EXECUTION_STATUS VARCHAR(32) NOT NULL",
                "CONFIDENCE DECIMAL(7,6)", "ATTEMPT_NO INTEGER",
                "PRECEDING_ASSESSMENT_SOURCE VARCHAR(32)",
                "ASSESSMENT_SOURCE VARCHAR(32) NOT NULL", "INPUT_FINGERPRINT VARCHAR(64) NOT NULL",
                "ASSESSMENT_FENCE_KEY VARCHAR(64) NOT NULL",
                "DECIDED_BY VARCHAR(36) NOT NULL",
                "CONSTRAINT PK_PROJECT_FILE_ROLE_ASSESSMENT PRIMARY KEY (ASSESSMENT_ID)",
                "CONSTRAINT UK_PROJECT_FILE_ROLE_ASSESSMENT_FENCE UNIQUE (FILE_ID, ASSESSMENT_FENCE_KEY)",
                "CONSTRAINT UK_PROJECT_FILE_ROLE_ASSESSMENT_SOURCE_BINDING UNIQUE (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, ASSESSMENT_SOURCE)",
                "CONSTRAINT UK_PROJECT_FILE_ROLE_DECISION_VERSION UNIQUE (FILE_ID, VERSION_NO)",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_ASSESSMENT_PARSE FOREIGN KEY (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_ASSESSMENT_PRECEDING FOREIGN KEY (PRECEDING_DETERMINISTIC_ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, PRECEDING_ASSESSMENT_SOURCE)",
                "REFERENCES PROJECT_FILE_ROLE_ASSESSMENT (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, ASSESSMENT_SOURCE)",
                "PRECEDING_ASSESSMENT_SOURCE = 'MACHINE_DETERMINISTIC'",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_EVIDENCE_ASSESSMENT FOREIGN KEY (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION) REFERENCES PROJECT_FILE_ROLE_ASSESSMENT (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION)",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_DECISION_ASSESSMENT FOREIGN KEY (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION) REFERENCES PROJECT_FILE_ROLE_ASSESSMENT (ASSESSMENT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION)",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_DECISION_DECIDED_BY FOREIGN KEY (DECIDED_BY) REFERENCES SYS_USER (ID)",
                "CONSTRAINT UK_PROJECT_FILE_ROLE_DECISION_OWNERSHIP UNIQUE (DECISION_ID, PROJECT_ID, FILE_ID)",
                "CONSTRAINT FK_PROJECT_FILE_ROLE_DECISION_SUPERSEDES FOREIGN KEY (SUPERSEDES_DECISION_ID, PROJECT_ID, FILE_ID)",
                "CK_PROJECT_FILE_ROLE_ASSESSMENT_EXECUTION", "'PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'UNKNOWN'",
                "EXECUTION_STATUS IN ('PENDING', 'RUNNING', 'UNKNOWN') AND ASSESSMENT_FENCE_KEY = INPUT_FINGERPRINT",
                "EXECUTION_STATUS IN ('COMPLETED', 'FAILED') AND ASSESSMENT_FENCE_KEY = ASSESSMENT_ID",
                "CK_PROJECT_FILE_ROLE_DECISION_STATUS", "DECISION_STATUS = 'CONFIRMED'",
                "IDX_PROJECT_FILE_ROLE_ASSESSMENT_FILE", "IDX_PROJECT_FILE_ROLE_EVIDENCE_ASSESSMENT",
                "IDX_PROJECT_FILE_ROLE_DECISION_FILE");
    }

    @Test
    void evidenceAndDecisionMappersAreInsertOnlyWhileAssessmentHasOnlyCasTransitionUpdate() throws Exception {
        String assessment = Files.readString(Path.of("src/main/resources/mappers/dm8/ProjectFileRoleAssessmentMapper.xml"));
        String evidence = Files.readString(Path.of("src/main/resources/mappers/dm8/ProjectFileRoleEvidenceMapper.xml"));
        String decision = Files.readString(Path.of("src/main/resources/mappers/dm8/ProjectFileRoleDecisionMapper.xml"));
        assertThat(assessment.toUpperCase(Locale.ROOT)).contains("<UPDATE ID=\"COMPAREANDSET\"");
        assertThat(count(assessment.toUpperCase(Locale.ROOT), "<UPDATE ")).isEqualTo(1);
        String normalizedAssessment = assessment.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(normalizedAssessment).contains(
                "PROJECT_ID=#{EXPECTED.PROJECTID}", "FILE_ID=#{EXPECTED.FILEID}",
                "FILE_SHA256=#{EXPECTED.FILESHA256}", "PARSE_RESULT_ID=#{EXPECTED.PARSERESULTID}",
                "PARSE_VERSION=#{EXPECTED.PARSEVERSION}", "ASSESSMENT_SOURCE=#{EXPECTED.ASSESSMENTSOURCE}",
                "ASSESSMENT_VERSION=#{EXPECTED.ASSESSMENTVERSION}",
                "ROLE_POLICY_VERSION=#{EXPECTED.ROLEPOLICYVERSION}",
                "INPUT_FINGERPRINT=#{EXPECTED.INPUTFINGERPRINT}",
                "ASSESSMENT_FENCE_KEY=#{EXPECTED.ASSESSMENTFENCEKEY}",
                "ASSESSMENT_FENCE_KEY=#{ROW.ASSESSMENTFENCEKEY}",
                "ATTEMPT_NO=#{EXPECTED.ATTEMPTNO}",
                "STARTED_AT = CASE WHEN #{ROW.EXECUTIONSTATUS} = 'RUNNING' THEN #{ROW.STARTEDAT} ELSE STARTED_AT END",
                "PRECEDING_DETERMINISTIC_ASSESSMENT_ID IS NULL AND #{EXPECTED.PRECEDINGDETERMINISTICASSESSMENTID} IS NULL",
                "MODEL_PROVIDER IS NULL AND #{EXPECTED.MODELPROVIDER} IS NULL",
                "PRECEDING_ASSESSMENT_SOURCE IS NULL AND #{EXPECTED.PRECEDINGASSESSMENTSOURCE} IS NULL",
                "MODEL_NAME IS NULL AND #{EXPECTED.MODELNAME} IS NULL",
                "MODEL_REVISION IS NULL AND #{EXPECTED.MODELREVISION} IS NULL",
                "PROMPT_VERSION IS NULL AND #{EXPECTED.PROMPTVERSION} IS NULL",
                "SCHEMA_VERSION IS NULL AND #{EXPECTED.SCHEMAVERSION} IS NULL");
        assertThat(normalizedAssessment).doesNotContain("CREATED_AT=#{EXPECTED.CREATEDAT}");
        assertThat(normalizedAssessment).contains(
                "<SELECT ID=\"FINDACTIVEBYFILEIDANDINPUTFINGERPRINT\"",
                "FILE_ID=#{FILEID} AND INPUT_FINGERPRINT=#{INPUTFINGERPRINT}",
                "EXECUTION_STATUS IN ('PENDING', 'RUNNING', 'UNKNOWN')",
                "ORDER BY PARSE_VERSION DESC, CREATED_AT DESC, CASE WHEN ASSESSMENT_SOURCE = 'MACHINE_AI' THEN 1 ELSE 0 END DESC, ASSESSMENT_ID DESC");
        assertThat(evidence.toUpperCase(Locale.ROOT)).doesNotContain("<UPDATE", "UPDATE ", "<DELETE", "DELETE ");
        assertThat(decision.toUpperCase(Locale.ROOT)).doesNotContain("<UPDATE", "UPDATE ", "<DELETE", "DELETE ");
    }

    private static String normalized() throws Exception {
        return Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
