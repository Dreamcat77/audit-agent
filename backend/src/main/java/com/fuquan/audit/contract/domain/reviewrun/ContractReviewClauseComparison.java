package com.fuquan.audit.contract.domain.reviewrun;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Versioned typed bilateral clause values owned by one persisted comparison finding. */
public record ContractReviewClauseComparison(UUID comparisonId, UUID findingId, UUID runId,
        UUID stepId, UUID attemptId, String comparisonKey, String kind, String result,
        int schemaVersion, List<Value> values) {
    public ContractReviewClauseComparison {
        Objects.requireNonNull(comparisonId); Objects.requireNonNull(findingId);
        Objects.requireNonNull(runId); Objects.requireNonNull(stepId); Objects.requireNonNull(attemptId);
        required(comparisonKey, 200, "comparisonKey");
        if (!List.of("BREACH", "TERM").contains(kind)) throw new IllegalArgumentException("invalid clause kind");
        if (!List.of("MATCH", "DIFFERENT", "NOT_EVALUATED", "CONFLICT").contains(result))
            throw new IllegalArgumentException("invalid clause comparison result");
        if (schemaVersion != 2) throw new IllegalArgumentException("typed comparison schema must be v2");
        values = List.copyOf(values);
        if (values.size() < 2 || values.stream().anyMatch(value ->
                !value.comparisonId().equals(comparisonId) || !value.runId().equals(runId)))
            throw new IllegalArgumentException("typed comparison requires two bound sides");
        if (values.stream().map(Value::sideKey).distinct().count() != values.size())
            throw new IllegalArgumentException("typed comparison side identity is duplicated");
        boolean hasConflict = values.stream().anyMatch(value -> "CONFLICT".equals(value.state()));
        boolean allKnown = values.stream().allMatch(value -> "KNOWN".equals(value.state()));
        if ((hasConflict && !"CONFLICT".equals(result))
                || (!hasConflict && !allKnown && !"NOT_EVALUATED".equals(result)))
            throw new IllegalArgumentException("typed comparison result is incoherent with side states");
    }

    public record Value(UUID valueId, UUID comparisonId, UUID runId, String sideKey,
            UUID sourceFileId, String state, String scenarioKey, String responsibleParty,
            String calculationBasis, BigDecimal rate, String rateUnit, String frequency,
            String period, BigDecimal capRate, BigDecimal capAmount, String terminationThreshold,
            String dateType, String normalizedDate, String termStart, String duration,
            String effectiveCondition, String terminationCondition, List<UUID> evidenceIds) {
        public Value {
            Objects.requireNonNull(valueId); Objects.requireNonNull(comparisonId);
            Objects.requireNonNull(runId);
            required(sideKey, 140, "sideKey"); required(state, 32, "state");
            required(scenarioKey, 200, "scenarioKey"); Objects.requireNonNull(sourceFileId);
            optional(responsibleParty, 200, "responsibleParty");
            optional(calculationBasis, 200, "calculationBasis");
            optional(rateUnit, 64, "rateUnit"); optional(frequency, 64, "frequency");
            optional(period, 100, "period");
            optional(terminationThreshold, 500, "terminationThreshold");
            optional(dateType, 100, "dateType"); optional(normalizedDate, 100, "normalizedDate");
            optional(termStart, 100, "termStart"); optional(duration, 200, "duration");
            optional(effectiveCondition, 1000, "effectiveCondition");
            optional(terminationCondition, 1000, "terminationCondition");
            if (!List.of("KNOWN", "NOT_EXTRACTED", "NOT_APPLICABLE", "CONFLICT").contains(state))
                throw new IllegalArgumentException("invalid typed side state");
            evidenceIds = List.copyOf(evidenceIds);
            if (evidenceIds.stream().distinct().count() != evidenceIds.size())
                throw new IllegalArgumentException("typed side evidence identity is duplicated");
            int requiredEvidence = "CONFLICT".equals(state) ? 2 : "KNOWN".equals(state) ? 1 : 0;
            if (evidenceIds.size() < requiredEvidence
                    || (requiredEvidence == 0 && !evidenceIds.isEmpty()))
                throw new IllegalArgumentException("typed side evidence identity is incoherent");
        }
    }

    private static void required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(name + " is invalid");
    }

    private static void optional(String value, int max, String name) {
        if (value != null && (value.isBlank() || value.length() > max))
            throw new IllegalArgumentException(name + " is invalid");
    }
}
