package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8ProcurementApprovalRepositoryTest {

    @Mock
    private ProcurementApprovalInstanceMapper instanceMapper;
    @Mock
    private ProcurementApprovalNodeMapper nodeMapper;
    @Mock
    private ProcurementApprovalOperationMapper operationMapper;

    private Dm8ProcurementApprovalRepository repository;

    @BeforeEach
    void setUp() {
        repository = new Dm8ProcurementApprovalRepository(instanceMapper, nodeMapper, operationMapper);
    }

    @Test
    void pendingTaskAllowsNullableProjectDisplayFields() {
        ProcurementApprovalTaskPersistenceRow row = requiredTaskRow();
        row.setProjectCode(null);
        row.setProjectName(null);
        row.setBudgetAmount(null);
        row.setStartedTime(null);
        when(nodeMapper.findPendingTasksByAssignee("reviewer-user")).thenReturn(List.of(row));

        var tasks = repository.findPendingTasksByAssignee("reviewer-user");

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.projectId()).isEqualTo(UUID.fromString(row.getProjectId()));
            assertThat(task.projectCode()).isNull();
            assertThat(task.projectName()).isNull();
            assertThat(task.budgetAmount()).isNull();
            assertThat(task.startedTime()).isNull();
            assertThat(task.assigneeUserId()).isEqualTo("reviewer-user");
        });
    }

    @Test
    void pendingTaskReportsMissingRequiredIdentifierClearly() {
        ProcurementApprovalTaskPersistenceRow row = requiredTaskRow();
        row.setProjectId(null);
        when(nodeMapper.findPendingTasksByAssignee("reviewer-user")).thenReturn(List.of(row));

        assertThatThrownBy(() -> repository.findPendingTasksByAssignee("reviewer-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("approval task query returned blank projectId");
    }

    private static ProcurementApprovalTaskPersistenceRow requiredTaskRow() {
        ProcurementApprovalTaskPersistenceRow row = new ProcurementApprovalTaskPersistenceRow();
        row.setProjectId("00000000-0000-0000-0000-000000000101");
        row.setInstanceId("00000000-0000-0000-0000-000000000201");
        row.setInstanceStatus("IN_PROGRESS");
        row.setNodeId("00000000-0000-0000-0000-000000000301");
        row.setNodeCode("REQUIREMENT_REVIEW");
        row.setNodeName("需求审查");
        row.setRequiredRoleCode("PROCUREMENT_REVIEWER");
        row.setAssigneeUserId("reviewer-user");
        row.setAssigneeName("采购审查员");
        return row;
    }
}
