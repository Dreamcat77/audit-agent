package com.fuquan.audit.contract.domain.reviewrun;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractReviewClauseComparisonTest {
    @Test
    void rejectsASideBoundToAnotherReviewRun() {
        UUID comparisonId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> new ContractReviewClauseComparison(comparisonId,
                UUID.randomUUID(), runId, UUID.randomUUID(), UUID.randomUUID(),
                "TERM_DATE:DELIVERY_DEADLINE", "TERM", "NOT_EVALUATED", 2, List.of(
                        missingSide(comparisonId, runId, "CONTRACT"),
                        missingSide(comparisonId, UUID.randomUUID(), "TENDER"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound sides");
    }

    private static ContractReviewClauseComparison.Value missingSide(
            UUID comparisonId, UUID runId, String sideKey) {
        return new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, runId,
                sideKey, UUID.randomUUID(), "NOT_EXTRACTED", "DELIVERY_DEADLINE",
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, List.of());
    }
}
