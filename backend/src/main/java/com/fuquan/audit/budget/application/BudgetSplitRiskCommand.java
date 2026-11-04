package com.fuquan.audit.budget.application;

import java.util.List;
import java.util.UUID;

public record BudgetSplitRiskCommand(
        List<UUID> procurementIds,
        String idempotencyKey,
        String requestId,
        String traceId,
        String sourceIp) {
    public BudgetSplitRiskCommand {
        procurementIds = procurementIds == null ? List.of() : List.copyOf(procurementIds);
    }
}
