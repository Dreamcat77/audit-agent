package com.fuquan.audit.contract.application;

/** 合同目录的规范化分页查询。 */
public record ContractListQuery(int page, int size, String status, String keyword) {
    public ContractListQuery {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid contract page request");
        }
        status = normalize(status);
        keyword = normalize(keyword);
        if (keyword != null && keyword.length() > 128) {
            throw new IllegalArgumentException("contract keyword exceeds maximum length");
        }
    }

    public long offset() {
        return (long) (page - 1) * size;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
