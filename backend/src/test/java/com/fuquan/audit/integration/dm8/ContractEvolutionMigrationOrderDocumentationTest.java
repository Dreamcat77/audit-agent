package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractEvolutionMigrationOrderDocumentationTest {
    @Test
    void readmeListsEveryControlledMigrationInDependencyOrder() throws Exception {
        String readme = Files.readString(Path.of("..", "db", "dm8", "README.md"));
        String[] ordered = {
                "CONTRACT__project_file_parse_result.sql",
                "CONTRACT__project_file_role_assessment.sql",
                "CONTRACT__contract_candidate_authority.sql",
                "CONTRACT__project_file_identity_assessment.sql",
                "CONTRACT__contract_candidate_support_authority.sql",
                "CONTRACT__review_session_project_file_binding.sql",
                "CONTRACT__contract_candidate_confirmation.sql",
                "CONTRACT__review_run.sql",
                "CONTRACT__review_run_results.sql",
                "CONTRACT__sanitized_debug_bundle.sql",
                "CONTRACT__review_ack_report.sql",
                "CONTRACT__contract_final_closeout.sql",
                "CONTRACT__contract_final_closeout_fact_extraction.sql",
                "CONTRACT__contract_final_closeout_typed_clause.sql"
        };
        int previous = -1;
        for (String migration : ordered) {
            int current = readme.indexOf(migration, previous + 1);
            assertThat(current).as(migration).isGreaterThan(previous);
            previous = current;
        }
        assertThat(readme).contains(
                "CONTROLLED_MANUAL",
                "does not allocate a V023+ migration",
                "dependency metadata");
        String contractNamespace = readme.substring(
                readme.indexOf("## Contract evolution migration namespace"),
                readme.indexOf("## Preflight classification"));
        assertThat(contractNamespace).doesNotContain(
                "V023__", "DROP CONSTRAINT", "BACKFILL", "STARTUP MIGRATION");
    }
}
