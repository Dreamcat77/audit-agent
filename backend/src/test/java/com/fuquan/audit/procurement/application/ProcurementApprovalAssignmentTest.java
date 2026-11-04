package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementApprovalAssignmentTest {

    @Test
    void assignsByRoleAndBuildsF04Path() {
        UUID projectId = UUID.randomUUID();
        ProcurementProject project = project(projectId, "30000.00");
        FakeRepository repository = new FakeRepository();
        ProcurementApprovalWorkflowService service = service(project, repository, new Users());

        var workflow = service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"));

        assertThat(workflow.nodes()).extracting(node -> node.nodeCode())
                .containsExactly("SUBMISSION", "REQUIREMENT_REVIEW", "BUDGET_REVIEW",
                        "REQUEST_APPROVAL", "FINANCE_DEPARTMENT_APPROVAL");
        assertThat(workflow.nodes().get(1).assigneeUserId()).isEqualTo("reviewer");
        assertThat(workflow.nodes().get(2).assigneeUserId()).isEqualTo("finance");
        assertThat(workflow.nodes().get(3).assigneeUserId()).isEqualTo("leader");
        assertThat(workflow.nodes().get(4).assigneeUserId()).isEqualTo("finance");
    }

    @Test
    void rejectsNonAssigneeAndRecordsAdminActing() {
        UUID projectId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository();
        ProcurementApprovalWorkflowService service = service(project(projectId, "9000"), repository, new Users());
        service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"));

        assertThatThrownBy(() -> service.approve(projectId, user("other", "PROCUREMENT_REVIEWER"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是该审批节点的审批人");

        service.approve(projectId, user("admin", "SYSTEM_ADMIN"), "管理员代审");

        assertThat(repository.operations).last().satisfies(operation -> {
            assertThat(operation.operationType()).isEqualTo("APPROVE");
            assertThat(operation.adminActing()).isTrue();
        });
    }

    @Test
    void returnedProjectRequiresAChangeAndModificationNoteBeforeStartingNewWorkflow() {
        UUID projectId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository();
        Users users = new Users();
        ProcurementProject original = project(projectId, "30000");
        Map<UUID, ProcurementProject> projects = new LinkedHashMap<>();
        projects.put(projectId, original);
        ProcurementApprovalWorkflowService service = service(original, repository, users, projects);
        var first = service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"));
        service.returnForModification(projectId, user("reviewer", "PROCUREMENT_REVIEWER"), "请修改后重新提交");

        assertThatThrownBy(() -> service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须填写修改说明");
        assertThatThrownBy(() -> service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"), "已修改"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未检测到针对退回意见的修改");

        projects.put(projectId, new ProcurementProject(original.id(), original.projectCode(),
                "测试采购需求（已修改）", original.subjectCode(), original.subjectName(), original.budgetAmount(),
                original.supplierName(), "DRAFT", original.createdTime(), original.departmentName(),
                original.procurementType(), original.procurementMethod(), original.requirementDescription(),
                original.applicantName(), original.attachmentName()));
        var second = service.start(projectId, user("applicant", "PROCUREMENT_APPLICANT"), "补充并调整项目名称");

        assertThat(second.instance().id()).isNotEqualTo(first.instance().id());
        assertThat(second.instance().status()).isEqualTo("IN_PROGRESS");
        assertThat(second.nodes()).extracting(node -> node.status())
                .containsExactly("COMPLETED", "IN_PROGRESS", "PENDING", "PENDING", "PENDING");
        assertThat(repository.workflows.get(first.instance().id()).instance().status()).isEqualTo("RETURNED");
        assertThat(repository.operations).extracting(ProcurementApprovalOperation::operationType)
                .containsExactly("START", "RETURN", "START");
        assertThat(repository.operations.get(2).opinion()).isEqualTo("修改说明：补充并调整项目名称");
    }

    private static ProcurementApprovalWorkflowService service(ProcurementProject project,
                                                               FakeRepository repository,
                                                               UserAccountRoleQueryPort users) {
        Map<UUID, ProcurementProject> projects = new LinkedHashMap<>();
        projects.put(project.id(), project);
        return service(project, repository, users, projects);
    }

    private static ProcurementApprovalWorkflowService service(ProcurementProject project,
                                                               FakeRepository repository,
                                                               UserAccountRoleQueryPort users,
                                                               Map<UUID, ProcurementProject> projects) {
        ProcurementProjectQueryService query = new ProcurementProjectQueryService(new QueryPort(projects));
        return new ProcurementApprovalWorkflowService(repository, query, new ProcurementProjectCommandPort() {
            @Override public UUID create(CreateCommand command) { throw new UnsupportedOperationException(); }
            @Override public void updateStatus(UUID projectId, String status) { }
        }, users, new ProcurementApprovalProperties());
    }

    private static ProcurementProject project(UUID id, String amount) {
        return new ProcurementProject(id, "PR-TEST", "测试采购需求", "M1001", "办公设备",
                new BigDecimal(amount), null, "DRAFT", Instant.now());
    }

    private static CurrentUser user(String id, String role) {
        return new CurrentUser(id, id, id, Set.of(role));
    }

    private static UserAccount account(String id, String role) {
        Instant now = Instant.now();
        return new UserAccount(id, id, "{noop}password", id, UserAccountStatus.ACTIVE,
                0, null, null, now, now, now, 0, Set.of(role));
    }

    private record QueryPort(Map<UUID, ProcurementProject> projects) implements ProcurementProjectQueryPort {
        @Override public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(new ArrayList<>(projects.values()), projects.size());
        }
        @Override public Optional<ProcurementProject> findById(UUID id) { return Optional.ofNullable(projects.get(id)); }
    }

    private static final class Users implements UserAccountRoleQueryPort {
        private final List<UserAccount> accounts = List.of(
                account("reviewer", "PROCUREMENT_REVIEWER"),
                account("finance", "FINANCE_REVIEWER"),
                account("leader", "PROCUREMENT_LEADER"),
                account("committee", "PROCUREMENT_COMMITTEE"));

        @Override public Optional<UserAccount> findFirstActiveByRole(String roleCode) {
            return accounts.stream().filter(account -> account.roles().contains(roleCode)).findFirst();
        }
        @Override public Optional<UserAccount> findActiveById(String userId) {
            return accounts.stream().filter(account -> account.id().equals(userId)).findFirst();
        }
    }

    private static final class FakeRepository implements ProcurementApprovalRepository {
        private final Map<UUID, Workflow> workflows = new LinkedHashMap<>();
        private final List<ProcurementApprovalOperation> operations = new ArrayList<>();
        @Override public Optional<Workflow> findByProjectId(UUID projectId) {
            return workflows.values().stream().filter(item -> item.instance().projectId().equals(projectId))
                    .reduce((first, second) -> second);
        }
        @Override public void create(Workflow workflow) { workflows.put(workflow.instance().id(), workflow); }
        @Override public void save(Workflow workflow) { workflows.put(workflow.instance().id(), workflow); }
        @Override public void appendOperation(ProcurementApprovalOperation operation) { operations.add(operation); }
        @Override public List<ProcurementApprovalOperation> findOperationsByProjectId(UUID projectId) {
            return operations.stream().filter(item -> item.projectId().equals(projectId)).toList();
        }
    }
}
