package com.fuquan.audit.contract.application;

import java.util.List;

/** 全局合同内审任务分页结果。 */
public record ReviewSessionTaskPage(
        List<ReviewSessionTaskSummary> items,
        int page,
        int size,
        long total,
        long totalPages) {
    public ReviewSessionTaskPage {
        items = List.copyOf(items);
    }

    public static ReviewSessionTaskPage of(
            List<ReviewSessionTaskSummary> items,
            ReviewSessionTaskQuery query,
            long total) {
        long pages = total == 0 ? 0 : (total + query.size() - 1) / query.size();
        return new ReviewSessionTaskPage(items, query.page(), query.size(), total, pages);
    }
}
