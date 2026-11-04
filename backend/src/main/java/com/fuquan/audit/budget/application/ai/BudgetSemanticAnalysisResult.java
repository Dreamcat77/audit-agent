package com.fuquan.audit.budget.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BudgetSemanticAnalysisResult(
        boolean available,
        List<Item> items,
        String model,
        long durationMs) {

    public BudgetSemanticAnalysisResult {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        model = requireText(model, "model");
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}")) {
            throw new IllegalArgumentException("model must be a safe identifier");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (!available && !items.isEmpty()) {
            throw new IllegalArgumentException("unavailable result must not contain analysis");
        }
    }

    public static BudgetSemanticAnalysisResult unavailable() {
        return new BudgetSemanticAnalysisResult(false, List.of(),
                "UNAVAILABLE_LOCAL_FAKE", 0L);
    }

    public record Item(
            UUID itemId,
            String suggestedCategory,
            String hint,
            Double confidence,
            String judgment,
            Double similarityScore,
            String enhancedDescription,
            String suggestion,
            Boolean needsConfirmation) {

        public Item(UUID itemId, String suggestedCategory, String hint, Double confidence,
                    String judgment, Double similarityScore, String enhancedDescription,
                    String suggestion) {
            this(itemId, suggestedCategory, hint, confidence, judgment, similarityScore,
                    enhancedDescription, suggestion, null);
        }

        public Item(UUID itemId, String suggestedCategory, String hint) {
            this(itemId, suggestedCategory, hint, null, null, null, null, null, null);
        }

        public Item {
            Objects.requireNonNull(itemId, "itemId must not be null");
            suggestedCategory = optionalText(suggestedCategory);
            hint = optionalText(hint);
            judgment = optionalText(judgment);
            enhancedDescription = optionalText(enhancedDescription);
            suggestion = optionalText(suggestion);
            if (suggestedCategory == null && hint == null && confidence == null
                    && judgment == null && similarityScore == null
                    && enhancedDescription == null && suggestion == null && needsConfirmation == null) {
                throw new IllegalArgumentException("budget analysis item has no result");
            }
            if (confidence != null && (confidence < 0 || confidence > 1)) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            if (similarityScore != null && (similarityScore < 0 || similarityScore > 1)) {
                throw new IllegalArgumentException("similarityScore must be between 0 and 1");
            }
        }
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
