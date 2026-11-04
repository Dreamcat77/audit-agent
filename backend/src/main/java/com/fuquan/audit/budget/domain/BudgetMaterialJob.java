package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.UUID;

/** ID is the real BudgetReviewTask ID. */
public record BudgetMaterialJob(UUID id, String actorId, String operationKey, String sha256,
        String status, String leaseToken, Instant leaseUntil, Instant updatedAt) {}
