package com.fuquan.audit.procurement.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 采购预审的 AI 辅助出站端口。
 *
 * <p>该端口只承载解释和建议，不承载规则状态或风险等级决策。</p>
 */
public interface ProcurementAgentReviewAiPort {

    Result analyze(Request request);

    record Request(UUID projectId, String projectCode, String projectName,
                   String departmentName, String procurementType, String procurementMethod,
                   String applicantName, String budgetAmount, List<Item> items) {
        public Request {
            Objects.requireNonNull(projectId, "projectId must not be null");
            if (projectCode == null || projectCode.isBlank()) {
                throw new IllegalArgumentException("projectCode must not be blank");
            }
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalArgumentException("projectName must not be blank");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("items must not be empty");
            }
        }
    }

    record Item(UUID itemId, String checkType, String checkName, String status,
                String ruleRiskLevel, String detail, String ruleSuggestion,
                String expectedValue, String actualValue) {
        public Item {
            Objects.requireNonNull(itemId, "itemId must not be null");
            if (checkName == null || checkName.isBlank()) {
                throw new IllegalArgumentException("checkName must not be blank");
            }
        }
    }

    record Result(boolean available, String model, long durationMs, String aiSummary,
                  List<ItemResult> items) {
        public Result {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must be non-negative");
            }
            if (!available && (!items.isEmpty() || aiSummary != null)) {
                throw new IllegalArgumentException("unavailable result must not contain analysis");
            }
        }

        public static Result unavailable() {
            return new Result(false, null, 0L, null, List.of());
        }
    }

    record ItemResult(UUID itemId, String aiExplanation, String aiSuggestion, Boolean needsConfirmation) {
        public ItemResult(UUID itemId, String aiExplanation, String aiSuggestion) {
            this(itemId, aiExplanation, aiSuggestion, null);
        }

        public ItemResult {
            Objects.requireNonNull(itemId, "itemId must not be null");
            aiExplanation = optionalText(aiExplanation);
            aiSuggestion = optionalText(aiSuggestion);
            if (aiExplanation == null && aiSuggestion == null) {
                throw new IllegalArgumentException("AI item must contain explanation or suggestion");
            }
        }

        private static String optionalText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
