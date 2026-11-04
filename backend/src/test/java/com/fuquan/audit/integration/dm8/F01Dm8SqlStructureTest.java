package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F01Dm8SqlStructureTest {

    @Test
    void migrationDefinesTheThreeF01TablesAndRequiredTypes() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V006__f01_budget_approval.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE PROCUREMENT_DEMAND")
                .contains("CREATE TABLE BUDGET_SUBJECT_MAPPING")
                .contains("CREATE TABLE BUDGET_APPROVAL_CHECK")
                .contains("AMOUNT             DECIMAL(18,2)")
                .contains("BUDGET_AMOUNT         DECIMAL(18,2)")
                .contains("IS_PASSED         BOOLEAN")
                .contains("CREATE INDEX IDX_BUDGET_CHECK_PROC");
    }

    @Test
    void mappersUseTheExpectedActiveAndNotDeletedPredicates() throws Exception {
        String demandMapper = resource("/mappers/dm8/ProcurementDemandMapper.xml");
        String mappingMapper = resource("/mappers/dm8/BudgetSubjectMappingMapper.xml");
        String checkMapper = resource("/mappers/dm8/BudgetApprovalCheckMapper.xml");

        assertThat(demandMapper)
                .contains("WHERE ID = #{id} AND DELETED = 0")
                .contains("BUDGET_APPROVAL_ID")
                .contains("SUBJECT_CODE")
                .contains("AMOUNT");
        assertThat(mappingMapper)
                .contains("WHERE SYSTEM_SUBJECT_CODE = #{systemSubjectCode} AND STATUS = 'ACTIVE'");
        assertThat(checkMapper)
                .contains("INSERT INTO BUDGET_APPROVAL_CHECK")
                .contains("IS_PASSED")
                .contains("#{row.passed}");
    }

    @Test
    void confirmationReloadsTheTaskWithADatabaseWriteLock() throws Exception {
        String taskMapper = resource("/mappers/dm8/BudgetReviewTaskMapper.xml");

        assertThat(taskMapper)
                .contains("id=\"findByIdForUpdate\"")
                .contains("FOR UPDATE");
    }

    @Test
    void materialReviewMigrationDefinesTaskAndItemTables() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V007__f01_material_review.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE BUDGET_REVIEW_TASK")
                .contains("CREATE TABLE BUDGET_REVIEW_ITEM")
                .contains("TEMP_FILE_PATH")
                .contains("DECLARED_AMOUNT       DECIMAL(18,2)")
                .contains("APPROVAL_AMOUNT       DECIMAL(18,2)")
                .contains("MATCH_DISTANCE        INT")
                .contains("DIFFERENCE_AMOUNT     DECIMAL(18,2)")
                .contains("CREATE INDEX IDX_BUDGET_REVIEW_ITEM_TASK");
    }

    @Test
    void materialCheckFieldsMigrationPreservesSourceAndApprovalFacts() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V008__f01_material_check_fields.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD SUBJECT_CODE VARCHAR(64)")
                .contains("ADD SUPPLIER_NAME VARCHAR(200)")
                .contains("ADD APPROVAL_SUBJECT_CODE VARCHAR(64)")
                .contains("ADD APPROVAL_SUPPLIER_NAME VARCHAR(200)");
    }

    @Test
    void validationRiskMigrationDefinesBatchAndRiskFields() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V012__f01_validation_risk_fields.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("BUDGET_YEAR")
                .contains("DEPARTMENT_CODE")
                .contains("PROJECT_CODE")
                .contains("RISK_LEVEL")
                .contains("MISSING_FIELDS")
                .contains("AMOUNT_FORMAT_ERRORS")
                .contains("CODE_FORMAT_ERROR")
                .contains("ABNORMAL_REASONS");
    }

    @Test
    void validationConfirmationMigrationDefinesPauseStateAndAuditLog() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V013__f01_validation_confirm_log.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD REVIEW_CURSOR INT")
                .contains("ADD PENDING_ROW_NUMBER INT")
                .contains("ADD PENDING_STEP VARCHAR(50)")
                .contains("ADD PENDING_REASON VARCHAR(500)")
                .contains("CREATE TABLE BUDGET_VALIDATION_CONFIRM_LOG")
                .contains("CONFIRMER_USER_ID VARCHAR(50) NOT NULL")
                .contains("CONFIRM_STEP VARCHAR(50) NOT NULL")
                .contains("CREATE INDEX IDX_CONFIRM_LOG_BATCH");
    }

    @Test
    void projectAndYearMigrationDefinesValidationConfiguration() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V014__f01_project_and_year_validation_config.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD REQUIRED_FIELDS_STATUS VARCHAR(16)")
                .contains("ADD AMOUNT_FORMAT_STATUS VARCHAR(16)")
                .contains("ADD PROJECT_CODE_STATUS VARCHAR(16)")
                .contains("ADD PROJECT_DEPARTMENT_STATUS VARCHAR(16)")
                .contains("ADD BUDGET_YEAR_VALIDITY_STATUS VARCHAR(16)")
                .contains("CREATE TABLE BUDGET_PROJECT_CONFIG")
                .contains("CREATE TABLE BUDGET_YEAR_VALIDITY_CONFIG")
                .contains("CREATE INDEX IDX_BUDGET_PROJECT_CONFIG_DEPT")
                .contains("CREATE INDEX IDX_BUDGET_YEAR_VALIDITY_STATUS");
    }

    @Test
    void categoryRuleMigrationDefinesClassificationConfigurationAndSeedData() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V015__f01_category_rules.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE BUDGET_CATEGORY_RULES")
                .contains("CATEGORY_CODE")
                .contains("ALLOWED_KEYWORDS")
                .contains("FORBIDDEN_KEYWORDS")
                .contains("SUGGEST_CATEGORY")
                .contains("30201")
                .contains("31002 办公设备购置")
                .contains("COMMIT");
    }

    @Test
    void f01V020AddsMapperRequiredCategoryAndAiHintColumns() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V020__f01_budget_semantic_analysis.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD CATEGORY_ATTRIBUTION_STATUS VARCHAR(16)")
                .contains("ADD CATEGORY_SUGGESTION VARCHAR(500)")
                .contains("ADD AI_SUGGESTED_CATEGORY VARCHAR(500)")
                .contains("ADD AI_HINT VARCHAR(2000)");
    }

    @Test
    void f01V021AddsAiCallProvenanceColumns() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V021__f01_ai_provenance.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD AI_MODEL VARCHAR(128)")
                .contains("ADD AI_DURATION_MS BIGINT");
    }

    @Test
    void f01V022AddsNodeSpecificAiResultColumns() throws Exception {
        String migration = Files.readString(
                Path.of("..", "db", "dm8", "V022__f01_ai_node_results.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD AI_CONFIDENCE DECIMAL(6,5)")
                .contains("ADD AI_JUDGMENT VARCHAR(200)")
                .contains("ADD AI_SIMILARITY_SCORE DECIMAL(6,5)")
                .contains("ADD AI_ENHANCED_DESCRIPTION VARCHAR(2000)")
                .contains("ADD AI_SUGGESTION VARCHAR(1000)");
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
