package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Required migration static gate; does not claim execution on DM8. */
class ProjectSessionMigrationStructureTest {
    @Test void additiveMigrationHasFrozenSourceAndCompositeAuthorityConstraints() throws Exception {
        String sql=Files.readString(Path.of("..","db","dm8","CONTRACT__review_session_project_file_binding.sql"));
        assertThat(sql).contains("MIGRATION-NAMESPACE: CONTRACT","EXECUTION: CONTROLLED_MANUAL","IDEMPOTENCY: NOT_ASSUMED",
                "CREATE TABLE REVIEW_SESSION_PROJECT_FILE_BINDING","SOURCE_KIND","PROJECT_FILE_REFERENCE","LEGACY_REVIEW_DOCUMENT",
                "REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "REFERENCES PROJECT_CONTRACT (PROJECT_ID, CONTRACT_ID)","SELECTION_VERSION", "ACTIVE_FLAG IN (0, 1)",
                "NOT_APPLIED","ALREADY_MATCHING","PARTIAL-SCHEMA_DRIFT","UNKNOWN");
        assertThat(sql).doesNotContain("DROP TABLE","DELETE FROM","BLOB","CLOB","AUTO_INCREMENT","JSONB");
    }
}
