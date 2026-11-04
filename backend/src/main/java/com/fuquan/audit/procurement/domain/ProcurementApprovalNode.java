package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 采购审批流程节点。 */
public record ProcurementApprovalNode(
        UUID id, UUID instanceId, String nodeCode, String nodeName, int nodeOrder,
        String status, String requiredRoleCode, String assigneeUserId, String assigneeName,
        Instant startedTime, Instant completedTime) {

    public ProcurementApprovalNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        if (nodeCode == null || nodeCode.isBlank()) throw new IllegalArgumentException("nodeCode must not be blank");
        if (nodeName == null || nodeName.isBlank()) throw new IllegalArgumentException("nodeName must not be blank");
        if (nodeOrder < 1) throw new IllegalArgumentException("nodeOrder must be positive");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
    }
}
