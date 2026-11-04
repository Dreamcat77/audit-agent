package com.fuquan.audit.budget.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface BudgetSemanticAnalysisPort {

    BudgetSemanticAnalysisResult analyzeBudgetSemantics(BudgetSemanticAnalysisRequest request);

    record BudgetSemanticAnalysisRequest(
            UUID sourceId,
            List<BudgetSemanticAnalysisItem> items) {

        public BudgetSemanticAnalysisRequest {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("items must not be empty");
            }
        }
    }

    record BudgetSemanticAnalysisItem(
            UUID itemId,
            String description,
            String currentCategory,
            String analysisType,
            String subjectCode,
            String categoryCode,
            String declaredSubjectName,
            String approvedSubjectName,
            String exceptionType,
            String exceptionData) {

        public BudgetSemanticAnalysisItem(
                UUID itemId,
                String description,
                String currentCategory) {
            this(itemId, description, currentCategory, "GENERAL",
                    null, null, null, null, null, null);
        }

        public BudgetSemanticAnalysisItem {
            Objects.requireNonNull(itemId, "itemId must not be null");
            Objects.requireNonNull(description, "description must not be null");
            if (description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }
            if (analysisType == null || analysisType.isBlank()) {
                analysisType = "GENERAL";
            }
        }
    }
}
