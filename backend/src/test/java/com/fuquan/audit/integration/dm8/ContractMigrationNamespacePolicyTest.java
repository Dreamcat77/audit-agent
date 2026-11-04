package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractMigrationNamespacePolicyTest {
    private static final Path DM8 = Path.of("..", "db", "dm8");

    @Test
    void numberedBaselineIncludesGoldenHistoryAndApprovedF06Line() throws Exception {
        List<String> numbered;
        try (var files = Files.list(DM8)) {
            numbered = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d{3}__.*\\.sql"))
                    .sorted().toList();
        }
        assertThat(numbered).containsExactly(
                "V001__audit_task_baseline.sql",
                "V002__local_account_auth_observability.sql",
                "V003__f02_contract_review.sql",
                "V004__contract_review_session.sql",
                "V005__contract_review_session_soft_delete.sql",
                "V006__f01_budget_approval.sql",
                "V007__f01_material_review.sql",
                "V008__f01_material_check_fields.sql",
                "V009__review_finding_acknowledgement.sql",
                "V010__project_intake_core.sql",
                "V011__project_contract_association.sql",
                "V012__f01_validation_risk_fields.sql",
                "V013__f01_validation_confirm_log.sql",
                "V014__f01_project_and_year_validation_config.sql",
                "V015__f01_category_rules.sql",
                "V016__f04_budget_approval_program.sql",
                "V017__project_file_parse_provenance.sql",
                "V018__f03_blacklist.sql",
                "V019__f03_blacklist_operation_log.sql",
                "V020__f01_budget_semantic_analysis.sql",
                "V021__f01_ai_provenance.sql",
                "V022__f01_ai_node_results.sql",
                "V023__f06_procurement_quote_alert.sql",
                "V024__procurement_project_creation.sql",
                "V025__procurement_approval_workflow.sql",
                "V026__procurement_review_report.sql",
                "V027__procurement_review_report_snapshot.sql",
                "V028__procurement_approval_assignment_and_thresholds.sql",
                "V029__procurement_approval_resubmission.sql",
                "V030__procurement_approval_return_snapshot.sql",
                "V031__procurement_notification.sql",
                "V032__operation_audit_details.sql",
                "V033__procurement_field_confirmation_audit.sql",
                "V034__procurement_agent_review_snapshot.sql",
                "V035__f07_procurement_tender_document.sql",
                "V036__f07_tender_rule_scan.sql",
                "V037__f07_tender_rule_catalog_expansion.sql",
                "V038__f07_fix_tender_rule_regex_boundaries.sql",
                "V039__f07_tender_ai_finding_metadata.sql",
                "V040__f07_tender_rule_legal_metadata.sql",
                "V041__procurement_review_workflow.sql",
                "V042__f08_procurement_collusion_review.sql",
                "V043__f11_split_purchase_assessment.sql",
                "V044__f05_budget_split_risk.sql",
                "V045__procurement_source_project.sql",
                "V046__business_material_intake.sql",
                "V047__procurement_multi_material_link.sql",
                "V048__procurement_material_order_100.sql",
                "V049__budget_subject_name_utf8_capacity.sql",
                "V050__procurement_material_async_queue.sql",
                "V051__budget_material_background.sql",
                "V052__budget_uploaded_material_batch.sql",
                "V053__budget_material_risk_results.sql",
                "V054__material_resumable_transfer.sql",
                "V055__material_extraction_ledger.sql",
                "V056__procurement_full_report_snapshot.sql",
                "V057__material_extraction_deadline.sql");
    }

    @Test
    void contractNamespaceIsDocumentedWithExactControlledOrderAndPreflight() throws Exception {
        String readme = Files.readString(DM8.resolve("README.md"));

        assertThat(readme).contains(
                "## Contract evolution migration namespace",
                "CONTRACT__project_file_parse_result.sql",
                "CONTRACT__project_file_role_assessment.sql",
                "CONTRACT__contract_candidate_authority.sql",
                "V010__project_intake_core.sql -> V017__project_file_parse_provenance.sql",
                "CONTRACT__project_file_parse_result.sql -> CONTRACT__project_file_role_assessment.sql",
                "CONTRACT__project_file_role_assessment.sql -> CONTRACT__contract_candidate_authority.sql",
                "CONTROLLED_MANUAL",
                "automatic migration runner",
                "filesystem sort",
                "SQL 文件存在不表示 customer DM8 已部署",
                "NOT_APPLIED", "ALREADY_MATCHING", "PARTIAL_SCHEMA_DRIFT", "UNKNOWN");
        assertThat(readme).contains("CONTRACT__contract_system_auto_authority.sql");
        assertThat(readme.indexOf("CONTRACT__project_file_parse_result.sql"))
                .isLessThan(readme.indexOf("CONTRACT__project_file_role_assessment.sql"));
    }

    @Test
    void contractSqlFilesDeclareNonNumericNamespaceIdentityAndDependency() throws Exception {
        String parse = Files.readString(DM8.resolve("CONTRACT__project_file_parse_result.sql"));
        String role = Files.readString(DM8.resolve("CONTRACT__project_file_role_assessment.sql"));
        String candidate = Files.readString(DM8.resolve("CONTRACT__contract_candidate_authority.sql"));

        assertThat(parse).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: project-file-parse-result",
                        "-- DEPENDS-ON: V010__project_intake_core.sql,V017__project_file_parse_provenance.sql",
                        "-- EXECUTION: CONTROLLED_MANUAL");
        assertThat(role).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: project-file-role-assessment",
                        "-- DEPENDS-ON: project-file-parse-result",
                        "-- EXECUTION: CONTROLLED_MANUAL");
        assertThat(candidate).startsWith("-- MIGRATION-NAMESPACE: CONTRACT\n")
                .contains("-- MIGRATION-ID: contract-candidate-authority",
                        "-- DEPENDS-ON: project-file-parse-result,project-file-role-assessment",
                        "-- EXECUTION: CONTROLLED_MANUAL");
    }
}
