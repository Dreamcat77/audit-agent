package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ReviewSessionStatus;

/** 全局合同内审任务的规范化分页查询。 */
public record ReviewSessionTaskQuery(
        int page,
        int size,
        ReviewSessionStatus status,
        String keyword) {
    public ReviewSessionTaskQuery {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid review session page request");
        }
        if (keyword == null || keyword.isBlank()) {
            keyword = null;
        } else {
            keyword = keyword.trim();
            if (keyword.length() > 128) {
                throw new IllegalArgumentException("review session keyword exceeds maximum length");
            }
        }
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
