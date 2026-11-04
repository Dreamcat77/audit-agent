package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractFinalCloseoutDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__contract_final_closeout.sql");

    @Test
    void amountOnlyMigrationIsControlledAdditiveAndKeepsDeclaredAmountRequired() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-final-closeout",
                        "-- DEPENDS-ON: V003__f02_contract_review.sql,contract-candidate-confirmation,review-run-results",
                        "-- EXECUTION: CONTROLLED_MANUAL",
                        "ALTER TABLE CONTRACT_LINE_ITEM ADD BASIS VARCHAR(32) DEFAULT 'QUANTITY_PRICE' NOT NULL",
                        "ALTER TABLE CONTRACT_LINE_ITEM DROP CONSTRAINT CK_CONTRACT_LINE_VALUES",
                        "ALTER TABLE CONTRACT_LINE_ITEM MODIFY QUANTITY DECIMAL(18,4) NULL",
                        "ALTER TABLE CONTRACT_LINE_ITEM MODIFY UNIT_PRICE DECIMAL(18,4) NULL",
                        "CONSTRAINT CK_CONTRACT_LINE_BASIS",
                        "BASIS = 'QUANTITY_PRICE' AND QUANTITY IS NOT NULL AND UNIT_PRICE IS NOT NULL",
                        "BASIS = 'AMOUNT_ONLY' AND QUANTITY IS NULL AND UNIT_PRICE IS NULL",
                        "DECLARED_AMOUNT >= 0")
                .doesNotContain("ALTER TABLE CONTRACT_LINE_ITEM MODIFY DECLARED_AMOUNT", "V023__");
    }
}
