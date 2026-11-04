package com.fuquan.audit.contract.application;

import java.util.List;
import java.util.Objects;

/** Contract-owned, read-only F02 progress projection. */
public record F02ReviewProgress(
        int total,
        List<F02ReviewProgressItem> items,
        String riskSummary) {

    public F02ReviewProgress {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (total != items.size()) {
            throw new IllegalArgumentException("total must equal projected item count");
        }
        Objects.requireNonNull(riskSummary, "riskSummary must not be null");
    }
}
