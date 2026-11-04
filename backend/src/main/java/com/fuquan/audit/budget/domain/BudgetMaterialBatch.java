package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable upload scope; a missing HTTP response never removes a member. */
public record BudgetMaterialBatch(UUID id, String actorId, String budgetYear, String departmentCode,
        List<Member> members, String status, String leaseToken, Instant leaseUntil, Instant createdAt) {
    public BudgetMaterialBatch { members = List.copyOf(members); }
    public record Member(UUID operationKey, String fileName, long fileSize) {}
    public record Source(UUID taskId, UUID batchId, String factsJson, String resultJson) {}
}
