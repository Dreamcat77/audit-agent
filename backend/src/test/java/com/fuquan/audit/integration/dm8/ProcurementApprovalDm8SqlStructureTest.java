package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementApprovalDm8SqlStructureTest {

    @Test
    void migrationDefinesDurableWorkflowTablesAndIntegrityConstraints() throws Exception {
        String sql = Files.readString(Path.of("..", "db", "dm8",
                "V025__procurement_approval_workflow.sql"), StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).contains(
                "CREATE TABLE PROCUREMENT_APPROVAL_INSTANCE",
                "CREATE TABLE PROCUREMENT_APPROVAL_NODE",
                "CREATE TABLE PROCUREMENT_APPROVAL_OPERATION_LOG",
                "UK_PROC_APPROVAL_INSTANCE_PROJECT",
                "UK_PROC_APPROVAL_NODE_ORDER",
                "FK_PROC_APPROVAL_INSTANCE_PROJECT",
                "FK_PROC_APPROVAL_NODE_INSTANCE",
                "FK_PROC_APPROVAL_OPERATION_INSTANCE",
                "IDX_PROC_APPROVAL_OPERATION_PROJECT",
                "COMMIT");
        assertThat(sql).contains("PROJECT_ID", "CURRENT_NODE_CODE", "NODE_CODE", "NODE_ORDER",
                "OPERATION_TYPE", "OPINION", "OPERATED_TIME");
        String resubmission = Files.readString(Path.of("..", "db", "dm8",
                "V029__procurement_approval_resubmission.sql"), StandardCharsets.UTF_8).toUpperCase();
        assertThat(resubmission).contains("DROP CONSTRAINT UK_PROC_APPROVAL_INSTANCE_PROJECT",
                "CREATE INDEX IDX_PROC_APPROVAL_INSTANCE_PROJECT");

        String snapshot = Files.readString(Path.of("..", "db", "dm8",
                "V030__procurement_approval_return_snapshot.sql"), StandardCharsets.UTF_8).toUpperCase();
        assertThat(snapshot).contains("ALTER TABLE PROCUREMENT_APPROVAL_INSTANCE ADD RETURN_SNAPSHOT", "COMMIT");

        String notification = Files.readString(Path.of("..", "db", "dm8",
                "V031__procurement_notification.sql"), StandardCharsets.UTF_8).toUpperCase();
        assertThat(notification).contains("CREATE TABLE PROCUREMENT_NOTIFICATION", "RECIPIENT_USER_ID",
                "READ_FLAG", "IDX_PROC_NOTIFICATION_RECIPIENT", "COMMIT");

        String audit = Files.readString(Path.of("..", "db", "dm8",
                "V032__operation_audit_details.sql"), StandardCharsets.UTF_8).toUpperCase();
        assertThat(audit).contains("ALTER TABLE AUDIT_OPERATION_LOG ADD RESOURCE_NAME",
                "ADD BEFORE_DATA", "ADD AFTER_DATA", "ADD FAILURE_REASON", "COMMIT");
        String confirmation = Files.readString(Path.of("..", "db", "dm8",
                "V033__procurement_field_confirmation_audit.sql"), StandardCharsets.UTF_8).toUpperCase();
        assertThat(confirmation).contains(
                "ALTER TABLE AUDIT_OPERATION_LOG ADD CONFIRMATION_FIELD_CODE",
                "ADD CONFIRMATION_FIELD_NAME",
                "ADD CONFIRMATION_FIELD_VALUE",
                "ADD CONFIRMATION_RULE_PROMPT",
                "ADD CONFIRMATION_AI_PROMPT",
                "ADD CONFIRMATION_NOTE",
                "COMMENT ON COLUMN AUDIT_OPERATION_LOG.CONFIRMATION_FIELD_CODE",
                "COMMIT");
    }

    @Test
    void pendingTaskQueryUsesExplicitColumnMapping() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProcurementApprovalNodeMapper.xml"), StandardCharsets.UTF_8).toUpperCase();

        assertThat(mapper).contains(
                "<RESULTMAP ID=\"TASKROW\"",
                "PROPERTY=\"PROJECTID\" COLUMN=\"PROJECT_ID\"",
                "PROPERTY=\"PROJECTCODE\" COLUMN=\"PROJECT_CODE\"",
                "PROPERTY=\"PROJECTNAME\" COLUMN=\"PROJECT_NAME\"",
                "PROPERTY=\"BUDGETAMOUNT\" COLUMN=\"BUDGET_AMOUNT\"",
                "<SELECT ID=\"FINDPENDINGTASKSBYASSIGNEE\" RESULTMAP=\"TASKROW\"");
    }
}
