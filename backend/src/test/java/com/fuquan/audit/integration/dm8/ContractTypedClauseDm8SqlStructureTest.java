package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractTypedClauseDm8SqlStructureTest {
    @Test
    void migrationStoresTypedSidesSeparatelyFromHumanSummaryAndBindsFrozenEvidence() throws Exception {
        String sql = Files.readString(Path.of("..", "db", "dm8",
                "CONTRACT__contract_final_closeout_typed_clause.sql"));
        assertThat(sql).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-final-closeout-typed-clause",
                        "-- EXECUTION: CONTROLLED_MANUAL",
                        "CREATE TABLE CONTRACT_REVIEW_CLAUSE_COMPARISON",
                        "CREATE TABLE CONTRACT_REVIEW_CLAUSE_VALUE",
                        "CREATE TABLE CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE",
                        "RUN_ID VARCHAR(36) NOT NULL",
                        "CONSTRAINT UK_CONTRACT_REVIEW_CLAUSE_RUN UNIQUE (COMPARISON_ID, RUN_ID)",
                        "(FINDING_ID, RUN_ID, STEP_ID, ATTEMPT_ID)",
                        "FOREIGN KEY (COMPARISON_ID, RUN_ID)",
                        "CALCULATION_BASIS VARCHAR(200)",
                        "RATE_VALUE DECIMAL(18,6)",
                        "CAP_RATE DECIMAL(18,6)",
                        "CAP_AMOUNT DECIMAL(18,6)",
                        "DATE_TYPE VARCHAR(100)",
                        "NORMALIZED_DATE VARCHAR(100)",
                        "EFFECTIVE_CONDITION VARCHAR(1000)",
                        "TERMINATION_CONDITION VARCHAR(1000)",
                        "FOREIGN KEY (RUN_ID, SOURCE_FILE_ID)",
                        "REFERENCES CONTRACT_REVIEW_RUN_MATERIAL (RUN_ID, FILE_ID)",
                        "STATE IN ('KNOWN','NOT_EXTRACTED','NOT_APPLICABLE','CONFLICT')",
                        "RESULT IN ('MATCH','DIFFERENT','NOT_EVALUATED','CONFLICT')",
                        "REFERENCES CONTRACT_REVIEW_EVIDENCE (EVIDENCE_ID)",
                        "PRIMARY KEY (VALUE_ID, EVIDENCE_ID)")
                .doesNotContain("RATE_OR_VALUE", "CAP_VALUE", "CONDITION_TEXT")
                .doesNotContain("ALTER TABLE CONTRACT_REVIEW_FINDING ADD SUMMARY", "V023__");
    }
}
