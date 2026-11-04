package com.fuquan.audit.budget.application;

import java.util.List;
import java.util.UUID;

public record BudgetApprovalValidationCommand(
        UUID taskId,
        UUID itemId,
        List<String> actualSteps,
        String idempotencyKey,
        String requestId,
        String traceId,
        String sourceIp) {
}
