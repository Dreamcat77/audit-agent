package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 审批流程追加操作日志。 */
public record ProcurementApprovalOperation(
        UUID id, UUID instanceId, UUID projectId, String nodeCode, String operationType,
        String operatorUserId, String operatorName, String opinion,
        String fromStatus, String toStatus, Instant operatedTime, Instant createdTime,
        String originalAssigneeUserId, String originalAssigneeName,
        String newAssigneeUserId, String newAssigneeName, boolean adminActing) {

    public ProcurementApprovalOperation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (nodeCode == null || nodeCode.isBlank()) throw new IllegalArgumentException("nodeCode must not be blank");
        if (operationType == null || operationType.isBlank()) throw new IllegalArgumentException("operationType must not be blank");
        if (operatorUserId == null || operatorUserId.isBlank()) throw new IllegalArgumentException("operatorUserId must not be blank");
        if (operatorName == null || operatorName.isBlank()) throw new IllegalArgumentException("operatorName must not be blank");
        Objects.requireNonNull(operatedTime, "operatedTime must not be null");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
    }

    public ProcurementApprovalOperation(UUID id, UUID instanceId, UUID projectId, String nodeCode,
                                       String operationType, String operatorUserId, String operatorName,
                                       String opinion, String fromStatus, String toStatus,
                                       Instant operatedTime, Instant createdTime) {
        this(id, instanceId, projectId, nodeCode, operationType, operatorUserId, operatorName, opinion,
                fromStatus, toStatus, operatedTime, createdTime, null, null, null, null, false);
    }
}
