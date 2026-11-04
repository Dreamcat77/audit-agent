package com.fuquan.audit.contract.application;

public record ContractAuditTaskSummary(
        long total,
        long pendingOrProcessing,
        long completed,
        long needsReview) {

    public ContractAuditTaskSummary {
        if (total < 0 || pendingOrProcessing < 0 || completed < 0 || needsReview < 0) {
            throw new IllegalArgumentException("contract audit task counts must be non-negative");
        }
    }
}
