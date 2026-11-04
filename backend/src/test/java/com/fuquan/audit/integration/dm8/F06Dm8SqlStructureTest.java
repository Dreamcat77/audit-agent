package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F06Dm8SqlStructureTest {

    private final Path migration = Path.of("..", "db", "dm8", "V023__f06_procurement_quote_alert.sql");

    @Test
    void migrationDefinesF06SummaryAndItemTablesWithDm8SafeNumericTypes() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "CREATE TABLE PROCUREMENT_QUOTE_CHECK",
                "CREATE TABLE PROCUREMENT_QUOTE_CHECK_ITEM",
                "BUDGET_AMOUNT             DECIMAL(18,2)",
                "QUOTE_AMOUNT        DECIMAL(18,2)",
                "COEFFICIENT_OF_VARIATION  DECIMAL(12,4)",
                "FOREIGN KEY (CHECK_ID)",
                "IDX_PROC_QUOTE_CHECK_BATCH",
                "IDX_PROC_QUOTE_ITEM_BATCH",
                "COMMIT");
        assertThat(sql).doesNotContain(" BOOLEAN", " JSON", " LIMIT ", "PASSWORD", "192.168.");
    }

    @Test
    void migrationConstrainsRiskAndBinaryFlags() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "RISK_LEVEL IN ('CRITICAL', 'WARNING', 'INFO')",
                "HIGHLY_CONCENTRATED IN (0, 1)",
                "OVER_BUDGET IN (0, 1)",
                "IS_SUSPICIOUS IN (0, 1)",
                "COMMENT ON TABLE PROCUREMENT_QUOTE_CHECK");
    }
}
