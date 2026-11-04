package com.fuquan.audit.contract.application.clause;

import java.util.List;
import java.util.UUID;

public record ContractTermClauseReview(
        List<DateItem> dates,
        List<ConditionItem> conditions,
        boolean materialInsufficient,
        List<ClauseComparison> comparisons,
        List<DeterministicCheck> deterministicChecks) {
    public ContractTermClauseReview(List<DateItem> dates, List<ConditionItem> conditions,
                                    boolean materialInsufficient, List<ClauseComparison> comparisons) {
        this(dates, conditions, materialInsufficient, comparisons, List.of());
    }

    public ContractTermClauseReview(List<DateItem> dates, List<ConditionItem> conditions,
                                    boolean materialInsufficient) {
        this(dates, conditions, materialInsufficient, List.of(), List.of());
    }

    public ContractTermClauseReview {
        dates = List.copyOf(dates);
        conditions = List.copyOf(conditions);
        comparisons = List.copyOf(comparisons);
        deterministicChecks = List.copyOf(deterministicChecks);
    }

    public record DateItem(String type, String source, String normalizedDate, String status,
                           UUID sourceFileId, String excerpt, boolean locatorAvailable) {
        public DateItem {
            if (locatorAvailable && (excerpt == null || excerpt.isBlank())) {
                throw new IllegalArgumentException("locatorAvailable requires a nonblank excerpt");
            }
        }

        public DateItem(String type, String source, String normalizedDate, String status,
                        UUID sourceFileId, String excerpt) {
            this(type, source, normalizedDate, status, sourceFileId, excerpt, false);
        }
    }

    public record ConditionItem(String type, String description, String status,
                                UUID sourceFileId, String excerpt, boolean locatorAvailable) {
        public ConditionItem {
            if (locatorAvailable && (excerpt == null || excerpt.isBlank())) {
                throw new IllegalArgumentException("locatorAvailable requires a nonblank excerpt");
            }
        }

        public ConditionItem(String type, String description, String status,
                             UUID sourceFileId, String excerpt) {
            this(type, description, status, sourceFileId, excerpt, false);
        }
    }

    public record DeterministicCheck(String type, String status, String summary,
                                     String recommendation, List<CheckSource> sources) {
        public DeterministicCheck {
            if (type == null || type.isBlank() || status == null || status.isBlank()
                    || summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("deterministic check fields are required");
            }
            sources = List.copyOf(sources);
        }
    }

    public record CheckSource(UUID fileId, String excerpt) {
        public CheckSource {
            if (fileId == null || excerpt == null || excerpt.isBlank()) {
                throw new IllegalArgumentException("deterministic check source is invalid");
            }
        }
    }
}
