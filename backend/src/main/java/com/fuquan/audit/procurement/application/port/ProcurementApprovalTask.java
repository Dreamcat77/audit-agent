package com.fuquan.audit.procurement.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 当前用户可处理的采购审批待办。 */
public record ProcurementApprovalTask(
        UUID projectId,
        String projectCode,
        String projectName,
        BigDecimal budgetAmount,
        UUID instanceId,
        String instanceStatus,
        UUID nodeId,
        String nodeCode,
        String nodeName,
        String requiredRoleCode,
        String assigneeUserId,
        String assigneeName,
        Instant startedTime) {
}
