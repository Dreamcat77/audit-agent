package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalTask;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 的采购审批内存仓储。 */
@Repository
@Profile("local")
public class InMemoryProcurementApprovalRepository implements ProcurementApprovalRepository {
    private final Map<UUID, Workflow> workflows = new LinkedHashMap<>();
    private final List<ProcurementApprovalOperation> operations = new ArrayList<>();

    @Override
    public synchronized Optional<Workflow> findByProjectId(UUID projectId) {
        return workflows.values().stream()
                .filter(item -> item.instance().projectId().equals(projectId))
                .max(java.util.Comparator.comparing(item -> item.instance().startedTime()));
    }

    @Override
    public synchronized void create(Workflow workflow) {
        workflows.put(workflow.instance().id(), workflow);
    }

    @Override
    public synchronized void save(Workflow workflow) {
        workflows.put(workflow.instance().id(), workflow);
    }

    @Override
    public synchronized void appendOperation(ProcurementApprovalOperation operation) {
        operations.add(operation);
    }

    @Override
    public synchronized List<ProcurementApprovalOperation> findOperationsByProjectId(UUID projectId) {
        return operations.stream().filter(item -> item.projectId().equals(projectId)).toList();
    }

    @Override
    public synchronized List<ProcurementApprovalTask> findPendingTasksByAssignee(String userId) {
        return workflows.values().stream()
                .flatMap(workflow -> workflow.nodes().stream()
                        .filter(node -> "IN_PROGRESS".equals(node.status()))
                        .filter(node -> userId.equals(node.assigneeUserId()))
                        .map(node -> new ProcurementApprovalTask(
                                workflow.instance().projectId(), null, null, null,
                                workflow.instance().id(), workflow.instance().status(), node.id(), node.nodeCode(),
                                node.nodeName(), node.requiredRoleCode(), node.assigneeUserId(), node.assigneeName(),
                                node.startedTime())))
                .toList();
    }
}
