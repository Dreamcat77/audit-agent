package com.fuquan.audit.contract.application;

import java.util.List;

/** 合同目录分页结果。 */
public record ContractPage(List<ContractSummary> items, int page, int size, long total, long totalPages) {
    public ContractPage {
        items = List.copyOf(items);
    }

    public static ContractPage of(List<ContractSummary> items, ContractListQuery query, long total) {
        long pages = total == 0 ? 0 : (total + query.size() - 1) / query.size();
        return new ContractPage(items, query.page(), query.size(), total, pages);
    }
}
