package com.fuquan.audit.contract.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ContractTextAnalysisResult(
        boolean available,
        List<String> summaries,
        List<Hint> hints,
        String model,
        long durationMs,
        List<ModelCall> modelCalls,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ContractMonetaryObservation monetaryObservation) {

    public ContractTextAnalysisResult {
        summaries = List.copyOf(Objects.requireNonNull(summaries, "summaries must not be null"));
        hints = List.copyOf(Objects.requireNonNull(hints, "hints must not be null"));
        modelCalls = List.copyOf(Objects.requireNonNull(modelCalls, "modelCalls must not be null"));
        model = requireText(model, "model");
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}")) {
            throw new IllegalArgumentException("model must be a safe identifier");
        }
        if (summaries.stream().anyMatch(summary -> summary == null || summary.isBlank())) {
            throw new IllegalArgumentException("summaries must not contain blank values");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (!available && (!summaries.isEmpty() || !hints.isEmpty())) {
            throw new IllegalArgumentException("unavailable result must not contain analysis");
        }
        if (available && monetaryObservation != null && "STRICT".equals(monetaryObservation.mode())
                && (!monetaryObservation.warnings().isEmpty() || !monetaryObservation.workWarnings().isEmpty()))
            throw new IllegalArgumentException("successful strict result cannot contain observed monetary warnings");
    }

    public ContractTextAnalysisResult(boolean available, List<String> summaries,
            List<Hint> hints, String model, long durationMs, List<ModelCall> modelCalls) {
        this(available, summaries, hints, model, durationMs, modelCalls, null);
    }

    public ContractTextAnalysisResult(boolean available, List<String> summaries,
            List<Hint> hints, String model, long durationMs) {
        this(available, summaries, hints, model, durationMs, List.of());
    }

    public static ContractTextAnalysisResult unavailable() {
        return new ContractTextAnalysisResult(false, List.of(), List.of(),
                "UNAVAILABLE_LOCAL_FAKE", 0L, List.of());
    }

    public enum CallPhase { TRANSPORT, REPAIR }
    public enum CallOutcome { SUCCEEDED, FAILED }

    public record ModelCall(int ordinal, CallPhase phase, CallOutcome outcome,
            String safeReason, long durationMs, String finishReason,
            Integer promptTokens, Integer completionTokens, Integer totalTokens,
            String provider, String model) {
        public ModelCall {
            if (ordinal <= 0 || durationMs < 0) throw new IllegalArgumentException("invalid model call counters");
            Objects.requireNonNull(phase); Objects.requireNonNull(outcome);
            provider = requireText(provider, "provider"); model = requireText(model, "model");
            if (safeReason != null && !safeReason.matches("[A-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("invalid model call reason");
            }
            for (Integer count : new Integer[]{promptTokens, completionTokens, totalTokens}) {
                if (count != null && count < 0) throw new IllegalArgumentException("negative model call count");
            }
        }
    }

    public enum SemanticCategory {
        CONTRACT_STRUCTURE,
        PROJECT_IDENTITY,
        SUPPLIER_IDENTITY,
        DELIVERY_TERMS,
        SERVICE_WARRANTY,
        SCOPE_AND_SUBCONTRACTING,
        BREACH_LIABILITY,
        TERM_TERMINATION,
        OTHER_TEXT
    }

    public enum Attention {
        INFO,
        REVIEW,
        INSUFFICIENT
    }

    public enum EvidenceStatus {
        NORMAL,
        REVIEW,
        INSUFFICIENT
    }

    public record EvidenceItem(UUID fileId, EvidenceStatus status, String excerpt,
                               boolean locatorAvailable) {
        public EvidenceItem {
            Objects.requireNonNull(fileId, "fileId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            excerpt = excerpt == null || excerpt.isBlank() ? null : excerpt.trim();
            if (locatorAvailable && excerpt == null) {
                throw new IllegalArgumentException("locatorAvailable requires an excerpt");
            }
        }
    }

    public record Hint(SemanticCategory category, Attention attention,
                       String title, String description, List<UUID> evidenceRefs,
                       List<EvidenceItem> evidenceItems) {

        public Hint(SemanticCategory category, Attention attention,
                    String title, String description, List<UUID> evidenceRefs) {
            this(category, attention, title, description, evidenceRefs, List.of());
        }

        public Hint {
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(attention, "attention must not be null");
            title = requireText(title, "title");
            description = requireText(description, "description");
            evidenceRefs = List.copyOf(Objects.requireNonNull(
                    evidenceRefs, "evidenceRefs must not be null"));
            evidenceItems = List.copyOf(Objects.requireNonNull(
                    evidenceItems, "evidenceItems must not be null"));
            if (evidenceRefs.stream().anyMatch(Objects::isNull)
                    || Set.copyOf(evidenceRefs).size() != evidenceRefs.size()) {
                throw new IllegalArgumentException("evidenceRefs must contain unique non-null values");
            }
            Set<UUID> evidenceRefSet = Set.copyOf(evidenceRefs);
            if (evidenceItems.stream().anyMatch(Objects::isNull)
                    || evidenceItems.stream().map(EvidenceItem::fileId).distinct().count() != evidenceItems.size()
                    || evidenceItems.stream().anyMatch(item -> !evidenceRefSet.contains(item.fileId()))) {
                throw new IllegalArgumentException("evidenceItems must be unique members of evidenceRefs");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
