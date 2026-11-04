package com.fuquan.audit.contract.application.clause;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BreachClauseReview(List<Item> items, boolean materialInsufficient,
                                 List<ClauseComparison> comparisons) {
    public BreachClauseReview(List<Item> items, boolean materialInsufficient) {
        this(items, materialInsufficient, List.of());
    }

    public BreachClauseReview {
        items = List.copyOf(items);
        comparisons = List.copyOf(comparisons);
    }

    public record Item(
            String scenario,
            BigDecimal rate,
            String rateUnit,
            String frequency,
            String calculationBasis,
            BigDecimal correspondingAmount,
            BigDecimal capRate,
            String capRateUnit,
            BigDecimal capAmount,
            BigDecimal actualPenaltyAmount,
            String terminationCondition,
            String status,
            String warning,
            UUID sourceFileId,
            String excerpt) {
    }
}
