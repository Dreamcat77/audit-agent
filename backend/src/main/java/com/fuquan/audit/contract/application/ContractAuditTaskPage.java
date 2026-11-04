package com.fuquan.audit.contract.application;

import java.util.List;

/** Page of latest direct-F02 audit tasks. */
public record ContractAuditTaskPage(
        List<ContractAuditTaskView> items,
        int page,
        int size,
        long total,
        long totalPages) {

    public ContractAuditTaskPage {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0 || totalPages < 0) {
            throw new IllegalArgumentException("invalid contract audit task page");
        }
    }
}
