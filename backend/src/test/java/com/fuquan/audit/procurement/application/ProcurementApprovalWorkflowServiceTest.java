package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.security.domain.CurrentUser;
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

class ProcurementApprovalWorkflowServiceTest {

    private static final CurrentUser APPLICANT = new CurrentUser("applicant", "applicant", "申请人", Set.of("PROCUREMENT_MANAGER"));
    private static final CurrentUser AUDITOR = new CurrentUser("auditor", "auditor", "审查人", Set.of("AUDITOR"));

    @Test
    void startsFourNodesAndPreventsApplicantFromApprovingOwnRequest() {
        Fixture fixture = fixture();
        var started = fixture.service.start(fixture.project.id(), APPLICANT);

        assertThat(started.instance().status()).isEqualTo("IN_PROGRESS");
        assertThat(started.instance().currentNodeCode()).isEqualTo("REQUIREMENT_REVIEW");
        assertThat(started.nodes()).extracting(node -> node.status())
                .containsExactly("COMPLETED", "IN_PROGRESS", "PENDING", "PENDING");
        assertThatThrownBy(() -> fixture.service.approve(fixture.project.id(), APPLICANT, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发起人不能审批");
    }

    @Test
    void approvalMovesThroughNodesAndCompletesProject() {
        Fixture fixture = fixture();
        fixture.service.start(fixture.project.id(), APPLICANT);

        fixture.service.addComment(fixture.project.id(), AUDITOR, "请核对采购方式");
        fixture.service.approve(fixture.project.id(), AUDITOR, "需求审查通过");
        fixture.service.approve(fixture.project.id(), AUDITOR, "预算审核通过");
        var completed = fixture.service.approve(fixture.project.id(), AUDITOR, "领导审批通过");

        assertThat(completed.instance().status()).isEqualTo("APPROVED");
        assertThat(completed.instance().currentNodeCode()).isNull();
        assertThat(fixture.projectStatuses.get(fixture.project.id())).isEqualTo("APPROVED");
        assertThat(fixture.operations).extracting(ProcurementApprovalOperation::operationType)
                .containsExactly("START", "COMMENT", "APPROVE", "APPROVE", "APPROVE");
        assertThatThrownBy(() -> fixture.service.approve(fixture.project.id(), AUDITOR, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可操作");
    }

    @Test
    void returnRequiresOpinionAndPersistsReturnedState() {
        Fixture fixture = fixture();
        fixture.service.start(fixture.project.id(), APPLICANT);
        assertThatThrownBy(() -> fixture.service.returnForModification(fixture.project.id(), AUDITOR, " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写审批意见");

        var returned = fixture.service.returnForModification(fixture.project.id(), AUDITOR, "请补充技术要求");
        assertThat(returned.instance().status()).isEqualTo("RETURNED");
        assertThat(fixture.projectStatuses.get(fixture.project.id())).isEqualTo("DRAFT");
        assertThat(fixture.service.history(fixture.project.id())).extracting(ProcurementApprovalOperation::operationType)
                .containsExactly("START", "RETURN");
    }

    private static Fixture fixture() {
        UUID id = UUID.randomUUID();
        ProcurementProject project = new ProcurementProject(id, "PR-TEST", "测试采购需求",
                "M1001", "办公设备", new BigDecimal("100.00"), null, "DRAFT", Instant.now());
        Map<UUID, ProcurementProject> projects = new LinkedHashMap<>();
        projects.put(id, project);
        MemoryApprovalRepository repository = new MemoryApprovalRepository();
        Map<UUID, String> statuses = new LinkedHashMap<>();
        ProcurementProjectQueryService query = new ProcurementProjectQueryService(new QueryPort(projects));
        ProcurementProjectCommandPort command = new ProcurementProjectCommandPort() {
            @Override public UUID create(CreateCommand command) { throw new UnsupportedOperationException(); }
            @Override public void updateStatus(UUID projectId, String status) { statuses.put(projectId, status); }
        };
        return new Fixture(new ProcurementApprovalWorkflowService(repository, query, command), project,
                statuses, repository.operations);
    }

    private record Fixture(ProcurementApprovalWorkflowService service, ProcurementProject project,
                           Map<UUID, String> projectStatuses, List<ProcurementApprovalOperation> operations) {}

    private record QueryPort(Map<UUID, ProcurementProject> projects) implements ProcurementProjectQueryPort {
        @Override public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(new ArrayList<>(projects.values()), projects.size());
        }
        @Override public Optional<ProcurementProject> findById(UUID id) { return Optional.ofNullable(projects.get(id)); }
    }

    private static final class MemoryApprovalRepository implements ProcurementApprovalRepository {
        private final Map<UUID, Workflow> workflows = new LinkedHashMap<>();
        private final List<ProcurementApprovalOperation> operations = new ArrayList<>();
        @Override public Optional<Workflow> findByProjectId(UUID projectId) {
            return workflows.values().stream().filter(item -> item.instance().projectId().equals(projectId)).findFirst();
        }
        @Override public void create(Workflow workflow) { workflows.put(workflow.instance().id(), workflow); }
        @Override public void save(Workflow workflow) { workflows.put(workflow.instance().id(), workflow); }
        @Override public void appendOperation(ProcurementApprovalOperation operation) { operations.add(operation); }
        @Override public List<ProcurementApprovalOperation> findOperationsByProjectId(UUID projectId) {
            return operations.stream().filter(item -> item.projectId().equals(projectId)).toList();
        }
    }
}
