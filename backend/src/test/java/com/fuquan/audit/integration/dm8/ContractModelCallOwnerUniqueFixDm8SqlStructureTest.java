package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractModelCallOwnerUniqueFixDm8SqlStructureTest {
    @Test
    void migrationReplacesNullableOwnerConstraintsWithOneOwnerAwareKey() throws Exception {
        Path migration = Path.of("..", "db", "dm8",
                "CONTRACT__contract_model_call_owner_unique_fix.sql");

        assertThat(Files.exists(migration)).isTrue();
        String sql = Files.readString(migration);
        String normalized = sql.replaceAll("\\s+", " ");

        assertThat(sql).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-model-call-owner-unique-fix",
                        "-- DEPENDS-ON: contract-fact-v2-audit",
                        "-- EXECUTION: CONTROLLED_MANUAL",
                        "CONSTRAINT UK_CONTRACT_MODEL_CALL_OWNER_CALL")
                .doesNotContain("DROP TABLE", "TRUNCATE", "DELETE FROM", "UPDATE ");
        assertThat(normalized).contains(
                "ALTER TABLE CONTRACT_MODEL_CALL_AUDIT DROP CONSTRAINT UK_CONTRACT_MODEL_CALL_FACT",
                "ALTER TABLE CONTRACT_MODEL_CALL_AUDIT DROP CONSTRAINT UK_CONTRACT_MODEL_CALL_REVIEW",
                "UNIQUE (FACT_EXTRACTION_ATTEMPT_ID, REVIEW_STEP_ATTEMPT_ID, ATTEMPT_NO, SCOPE, CALL_ORDINAL)");
    }
}
