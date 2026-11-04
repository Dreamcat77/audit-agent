package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F04Dm8SqlStructureTest {

    private final Path migration = Path.of("..", "db", "dm8", "V016__f04_budget_approval_program.sql");
    private final Path readOnlyProbe = Path.of(
            "..", "db", "dm8", "probes", "V016__f04_budget_approval_program_read_only.sql");

    @Test
    void migrationDefinesAdditiveDm8RuleAndValidationTables() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "CREATE TABLE BUDGET_APPROVAL_RULE_CONFIG",
                "CREATE TABLE BUDGET_APPROVAL_VALIDATION",
                "DECIMAL(18,2)",
                "PROCEDURE_ORDER_VALID SMALLINT",
                "AUTHORITY_MATCHED SMALLINT",
                "IS_PASSED SMALLINT",
                "CK_BUDGET_APPROVAL_MODE_GROUP",
                "COMMENT ON TABLE",
                "PM_DEMO_20260828",
                "MANUAL_TEST");
        assertThat(sql).doesNotContain(" BOOLEAN", " JSON", "ON UPDATE", "BUDGET_REVIEW_TASK ADD",
                "BUDGET_REVIEW_ITEM ADD", "PASSWORD", "192.168.");
    }

    @Test
    void seedRulesExpressNonOverlappingAuthorityAndCumulativeProcedure() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "'PROCEDURE_REQUIREMENT', 'CUMULATIVE_MIN'",
                "10000.00",
                "50000.00",
                "'AUTHORITY_LEVEL', 'RANGE'",
                "200000.00",
                "2000000.00",
                "待甲方审批权限矩阵最终确认");
        assertThat(sql.split("PM_DEMO_20260828", -1).length - 1).isGreaterThanOrEqualTo(5);
    }

    @Test
    void cumulativeRuleConstraintAllowsInclusiveOrExclusiveMinimums() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();
        String cumulativeConstraint = sql.substring(
                sql.indexOf("CONSTRAINT CK_BUDGET_APPROVAL_CUMULATIVE"),
                sql.indexOf("CONSTRAINT CK_BUDGET_APPROVAL_MIN_FLAG"));

        assertThat(cumulativeConstraint)
                .contains("MIN_AMOUNT IS NOT NULL", "MAX_AMOUNT IS NULL")
                .doesNotContain("MIN_INCLUSIVE = 1");
        assertThat(sql).contains("CK_BUDGET_APPROVAL_MIN_FLAG CHECK (MIN_INCLUSIVE IN (0, 1))");
    }

    @Test
    void v016ProbeIsReadOnlyAndClassifiesTablesColumnsConstraintsAndSeedRules() throws Exception {
        String sql = Files.readString(readOnlyProbe, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "USER_TABLES",
                "USER_TAB_COLUMNS",
                "USER_CONSTRAINTS",
                "BUDGET_APPROVAL_RULE_CONFIG",
                "BUDGET_APPROVAL_VALIDATION",
                "PM_DEMO_20260828");
        assertThat(sql).doesNotContain(
                "CREATE ",
                "ALTER ",
                "DROP ",
                "INSERT ",
                "UPDATE ",
                "DELETE ",
                "MERGE ",
                "EXECUTE ");
    }
}
