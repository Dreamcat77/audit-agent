package com.fuquan.audit.contract.application;

import java.time.Instant;

public record ContractAuditTaskListQuery(
        int page,
        int size,
        String keyword,
        Instant createdFrom,
        Instant createdTo,
        RiskFilter risk,
        StatusFilter status) {

    public ContractAuditTaskListQuery {
        if (page < 1 || size < 1) throw new IllegalArgumentException("page and size must be positive");
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (keyword != null && keyword.length() > 100) {
            throw new IllegalArgumentException("keyword must not exceed 100 characters");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must not be after createdTo");
        }
    }

    public boolean filtered() {
        return keyword != null || createdFrom != null || createdTo != null || risk != null || status != null;
    }

    public enum RiskFilter { NORMAL, REVIEW, INSUFFICIENT }

    public enum StatusFilter { PENDING, PROCESSING, COMPLETED, FAILED, NEEDS_REVIEW }
}
