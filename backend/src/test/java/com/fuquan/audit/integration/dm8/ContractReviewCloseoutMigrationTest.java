package com.fuquan.audit.integration.dm8;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractReviewCloseoutMigrationTest {
    @Test void ownsOnlyTwoNewTablesAndPreservesF02Truth() throws Exception {
        String sql = Files.readString(Path.of("../db/dm8/CONTRACT__review_ack_report.sql"));
        assertEquals(2, sql.split("CREATE TABLE ", -1).length - 1);
        for (String required : new String[]{"MIGRATION-NAMESPACE: CONTRACT", "MIGRATION-ID: review-ack-report",
                "DEPENDS-ON: review-run-results,V009__review_finding_acknowledgement.sql",
                "EXECUTION: CONTROLLED_MANUAL", "IDEMPOTENCY: NOT_ASSUMED",
                "CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT", "CONTRACT_REVIEW_REPORT",
                "UNIQUE (FINDING_ID)", "UNIQUE (RUN_ID, REPORT_VERSION)",
                "REFERENCES CONTRACT_REVIEW_FINDING (FINDING_ID, RUN_ID, STEP_ID, ATTEMPT_ID)",
                "SNAPSHOT_JSON CLOB NOT NULL", "'UNKNOWN'"}) assertTrue(sql.contains(required), required);
        assertFalse(sql.contains("ALTER TABLE"));
        assertFalse(sql.contains("DROP "));
    }
    @Test void preflightNeverTreatsPartialOrUnreadableCatalogAsAbsent() {
        var expected = Map.of("ACK", "column+FK+unique+check", "REPORT", "column+FK+unique+check");
        assertEquals("NOT_APPLIED", ContractReviewCloseoutSchemaPreflight.classify(expected, Map.of(), true));
        assertEquals("ALREADY_MATCHING", ContractReviewCloseoutSchemaPreflight.classify(expected, expected, true));
        assertEquals("PARTIAL-SCHEMA_DRIFT", ContractReviewCloseoutSchemaPreflight.classify(expected, Map.of("ACK", "wrong"), true));
        assertEquals("PARTIAL-SCHEMA_DRIFT", ContractReviewCloseoutSchemaPreflight.classify(expected, Map.of("ACK", "column+FK+unique+check"), true));
        assertEquals("UNKNOWN", ContractReviewCloseoutSchemaPreflight.classify(expected, Map.of(), false));
    }
}
