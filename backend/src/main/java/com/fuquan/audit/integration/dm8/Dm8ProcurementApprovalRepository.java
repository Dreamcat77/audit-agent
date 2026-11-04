package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalTask;
import com.fuquan.audit.procurement.domain.ProcurementApprovalInstance;
import com.fuquan.audit.procurement.domain.ProcurementApprovalNode;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 采购审批流程 DM8 持久化适配器。 */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementApprovalRepository implements ProcurementApprovalRepository {

    private final ProcurementApprovalInstanceMapper instanceMapper;
    private final ProcurementApprovalNodeMapper nodeMapper;
    private final ProcurementApprovalOperationMapper operationMapper;

    public Dm8ProcurementApprovalRepository(ProcurementApprovalInstanceMapper instanceMapper,
                                            ProcurementApprovalNodeMapper nodeMapper,
                                            ProcurementApprovalOperationMapper operationMapper) {
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.operationMapper = operationMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workflow> findByProjectId(UUID projectId) {
        ProcurementApprovalInstancePersistenceRow row = instanceMapper.findByProjectId(projectId.toString());
        if (row == null) return Optional.empty();
        return Optional.of(new Workflow(toDomain(row), nodeMapper.findByInstanceId(row.getId()).stream()
                .map(Dm8ProcurementApprovalRepository::toDomain).toList()));
    }

    @Override
    public void create(Workflow workflow) {
        instanceMapper.insert(toRow(workflow.instance()));
        List<ProcurementApprovalNodePersistenceRow> rows = workflow.nodes().stream()
                .map(Dm8ProcurementApprovalRepository::toRow).toList();
        if (!rows.isEmpty()) nodeMapper.insertBatch(rows);
    }

    @Override
    public void save(Workflow workflow) {
        instanceMapper.update(toRow(workflow.instance()));
        workflow.nodes().forEach(node -> nodeMapper.update(toRow(node)));
    }

    @Override
    public void appendOperation(ProcurementApprovalOperation operation) {
        operationMapper.insert(toRow(operation));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementApprovalOperation> findOperationsByProjectId(UUID projectId) {
        return operationMapper.findByProjectId(projectId.toString()).stream()
                .map(Dm8ProcurementApprovalRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementApprovalTask> findPendingTasksByAssignee(String userId) {
        return nodeMapper.findPendingTasksByAssignee(userId).stream()
                .map(Dm8ProcurementApprovalRepository::toTask)
                .toList();
    }

    private static ProcurementApprovalTask toTask(ProcurementApprovalTaskPersistenceRow row) {
        if (row == null) {
            throw new IllegalStateException("approval task query returned a null row");
        }
        return new ProcurementApprovalTask(
                requiredUuid(row.getProjectId(), "projectId"),
                row.getProjectCode(),
                row.getProjectName(),
                row.getBudgetAmount(),
                requiredUuid(row.getInstanceId(), "instanceId"),
                row.getInstanceStatus(),
                requiredUuid(row.getNodeId(), "nodeId"),
                row.getNodeCode(),
                row.getNodeName(),
                row.getRequiredRoleCode(),
                row.getAssigneeUserId(),
                row.getAssigneeName(),
                toInstant(row.getStartedTime()));
    }

    private static UUID requiredUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("approval task query returned blank " + fieldName);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("approval task query returned invalid " + fieldName, exception);
        }
    }

    private static ProcurementApprovalInstancePersistenceRow toRow(ProcurementApprovalInstance value) {
        ProcurementApprovalInstancePersistenceRow row = new ProcurementApprovalInstancePersistenceRow();
        row.setId(value.id().toString());
        row.setProjectId(value.projectId().toString());
        row.setStatus(value.status());
        row.setCurrentNodeCode(value.currentNodeCode());
        row.setStartedByUserId(value.startedByUserId());
        row.setStartedByName(value.startedByName());
        row.setStartedTime(toLocalDateTime(value.startedTime()));
        row.setCompletedTime(toLocalDateTime(value.completedTime()));
        row.setCreatedTime(toLocalDateTime(value.createdTime()));
        row.setUpdatedTime(toLocalDateTime(value.updatedTime()));
        row.setReturnSnapshot(value.returnSnapshot());
        return row;
    }

    private static ProcurementApprovalNodePersistenceRow toRow(ProcurementApprovalNode value) {
        ProcurementApprovalNodePersistenceRow row = new ProcurementApprovalNodePersistenceRow();
        row.setId(value.id().toString());
        row.setInstanceId(value.instanceId().toString());
        row.setNodeCode(value.nodeCode());
        row.setNodeName(value.nodeName());
        row.setNodeOrder(value.nodeOrder());
        row.setStatus(value.status());
        row.setRequiredRoleCode(value.requiredRoleCode());
        row.setAssigneeUserId(value.assigneeUserId());
        row.setAssigneeName(value.assigneeName());
        row.setStartedTime(toLocalDateTime(value.startedTime()));
        row.setCompletedTime(toLocalDateTime(value.completedTime()));
        return row;
    }

    private static ProcurementApprovalOperationPersistenceRow toRow(ProcurementApprovalOperation value) {
        ProcurementApprovalOperationPersistenceRow row = new ProcurementApprovalOperationPersistenceRow();
        row.setId(value.id().toString());
        row.setInstanceId(value.instanceId().toString());
        row.setProjectId(value.projectId().toString());
        row.setNodeCode(value.nodeCode());
        row.setOperationType(value.operationType());
        row.setOperatorUserId(value.operatorUserId());
        row.setOperatorName(value.operatorName());
        row.setOpinion(value.opinion());
        row.setFromStatus(value.fromStatus());
        row.setToStatus(value.toStatus());
        row.setOperatedTime(toLocalDateTime(value.operatedTime()));
        row.setCreatedTime(toLocalDateTime(value.createdTime()));
        row.setOriginalAssigneeUserId(value.originalAssigneeUserId());
        row.setOriginalAssigneeName(value.originalAssigneeName());
        row.setNewAssigneeUserId(value.newAssigneeUserId());
        row.setNewAssigneeName(value.newAssigneeName());
        row.setAdminActing(value.adminActing() ? 1 : 0);
        return row;
    }

    private static ProcurementApprovalInstance toDomain(ProcurementApprovalInstancePersistenceRow row) {
        return new ProcurementApprovalInstance(UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()),
                row.getStatus(), row.getCurrentNodeCode(), row.getStartedByUserId(), row.getStartedByName(),
                toInstant(row.getStartedTime()), toInstant(row.getCompletedTime()),
                toInstant(row.getCreatedTime()), toInstant(row.getUpdatedTime()), row.getReturnSnapshot());
    }

    private static ProcurementApprovalNode toDomain(ProcurementApprovalNodePersistenceRow row) {
        return new ProcurementApprovalNode(UUID.fromString(row.getId()), UUID.fromString(row.getInstanceId()),
                row.getNodeCode(), row.getNodeName(), row.getNodeOrder(), row.getStatus(),
                row.getRequiredRoleCode(), row.getAssigneeUserId(), row.getAssigneeName(), toInstant(row.getStartedTime()),
                toInstant(row.getCompletedTime()));
    }

    private static ProcurementApprovalOperation toDomain(ProcurementApprovalOperationPersistenceRow row) {
        return new ProcurementApprovalOperation(UUID.fromString(row.getId()), UUID.fromString(row.getInstanceId()),
                UUID.fromString(row.getProjectId()), row.getNodeCode(), row.getOperationType(),
                row.getOperatorUserId(), row.getOperatorName(), row.getOpinion(), row.getFromStatus(),
                row.getToStatus(), toInstant(row.getOperatedTime()), toInstant(row.getCreatedTime()),
                row.getOriginalAssigneeUserId(), row.getOriginalAssigneeName(), row.getNewAssigneeUserId(),
                row.getNewAssigneeName(), row.getAdminActing() != null && row.getAdminActing() != 0);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
