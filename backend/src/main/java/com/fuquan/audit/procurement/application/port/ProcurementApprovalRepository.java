package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementApprovalInstance;
import com.fuquan.audit.procurement.domain.ProcurementApprovalNode;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 审批流程持久化 Port，DM8 是正式事实源。 */
public interface ProcurementApprovalRepository {
    Optional<Workflow> findByProjectId(UUID projectId);
    void create(Workflow workflow);
    void save(Workflow workflow);
    void appendOperation(ProcurementApprovalOperation operation);
    List<ProcurementApprovalOperation> findOperationsByProjectId(UUID projectId);
    default List<ProcurementApprovalTask> findPendingTasksByAssignee(String userId) {
        return List.of();
    }

    record Workflow(ProcurementApprovalInstance instance, List<ProcurementApprovalNode> nodes) {
        public Workflow {
            nodes = List.copyOf(nodes);
        }
    }
}
