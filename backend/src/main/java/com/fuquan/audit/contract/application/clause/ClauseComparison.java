package com.fuquan.audit.contract.application.clause;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;

/** Additive cross-material comparison evidence for deterministic clause reviews. */
public record ClauseComparison(String key, String label, String status, String message,
        List<Source> sources, List<TypedSide> typedSides) {
    public ClauseComparison {
        Objects.requireNonNull(key);
        Objects.requireNonNull(label);
        Objects.requireNonNull(status);
        Objects.requireNonNull(message);
        sources = List.copyOf(sources);
        typedSides = List.copyOf(typedSides);
    }

    public ClauseComparison(String key, String label, String status, String message, List<Source> sources) {
        this(key, label, status, message, sources, List.of());
    }

    public record Source(UUID fileId, String fileName, String category, String excerpt) {
        public Source {
            Objects.requireNonNull(fileId);
            Objects.requireNonNull(fileName);
            Objects.requireNonNull(category);
            Objects.requireNonNull(excerpt);
        }
    }

    public record TypedSide(String sideKey, UUID fileId, String state, String scenarioKey,
            String responsibleParty, String calculationBasis, BigDecimal rate, String rateUnit,
            String frequency, String period, BigDecimal capRate, BigDecimal capAmount,
            String terminationThreshold, String dateType, String normalizedDate,
            String termStart, String duration, String effectiveCondition,
            String terminationCondition) {
        public TypedSide {
            Objects.requireNonNull(sideKey); Objects.requireNonNull(fileId);
            Objects.requireNonNull(state); Objects.requireNonNull(scenarioKey);
        }
    }
}
