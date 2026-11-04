package com.fuquan.audit.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BudgetReviewItem(
        UUID id,
        UUID taskId,
        Integer rowNumber,
        String subjectCode,
        String subjectName,
        BigDecimal declaredAmount,
        String supplierName,
        String projectCode,
        String approvalSubjectCode,
        String approvalSubjectName,
        BigDecimal approvalAmount,
        String approvalSupplierName,
        String matchStatus,
        String matchMethod,
        Integer matchDistance,
        String amountStatus,
        BigDecimal differenceAmount,
        String exceptionType,
        String errorDetail,
        String riskLevel,
        String missingFields,
        String amountFormatErrors,
        String codeFormatError,
        String requiredFieldsStatus,
        String amountFormatStatus,
        String categoryAttributionStatus,
        String categorySuggestion,
        String projectCodeStatus,
        String projectDepartmentStatus,
        String budgetYearValidityStatus,
        List<String> abnormalReasons,
        Instant createdTime,
        String aiSuggestedCategory,
        String aiHint,
        String aiModel,
        Long aiDurationMs,
        Double aiConfidence,
        String aiJudgment,
        Double aiSimilarityScore,
        String aiEnhancedDescription,
        String aiSuggestion) {

    public BudgetReviewItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (matchStatus == null || matchStatus.isBlank()) {
            throw new IllegalArgumentException("matchStatus must not be blank");
        }
        if (riskLevel == null || riskLevel.isBlank()) {
            throw new IllegalArgumentException("riskLevel must not be blank");
        }
        abnormalReasons = abnormalReasons == null ? List.of() : List.copyOf(abnormalReasons);
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        if (aiModel != null && aiModel.isBlank()) {
            throw new IllegalArgumentException("aiModel must not be blank");
        }
        if (aiDurationMs != null && aiDurationMs < 0) {
            throw new IllegalArgumentException("aiDurationMs must be non-negative");
        }
        if (aiConfidence != null && (aiConfidence < 0 || aiConfidence > 1)) {
            throw new IllegalArgumentException("aiConfidence must be between 0 and 1");
        }
        if (aiSimilarityScore != null && (aiSimilarityScore < 0 || aiSimilarityScore > 1)) {
            throw new IllegalArgumentException("aiSimilarityScore must be between 0 and 1");
        }
    }

    /**
     * Compatibility constructor for callers compiled against the pre-AI
     * F01 item shape.
     */
    public BudgetReviewItem(
            UUID id,
            UUID taskId,
            Integer rowNumber,
            String subjectCode,
            String subjectName,
            BigDecimal declaredAmount,
            String supplierName,
            String projectCode,
            String approvalSubjectCode,
            String approvalSubjectName,
            BigDecimal approvalAmount,
            String approvalSupplierName,
            String matchStatus,
            String matchMethod,
            Integer matchDistance,
            String amountStatus,
            BigDecimal differenceAmount,
            String exceptionType,
            String errorDetail,
            String riskLevel,
            String missingFields,
            String amountFormatErrors,
            String codeFormatError,
            String requiredFieldsStatus,
            String amountFormatStatus,
            String categoryAttributionStatus,
            String categorySuggestion,
            String projectCodeStatus,
            String projectDepartmentStatus,
            String budgetYearValidityStatus,
            List<String> abnormalReasons,
            Instant createdTime) {
        this(
                id, taskId, rowNumber, subjectCode, subjectName, declaredAmount,
                supplierName, projectCode, approvalSubjectCode, approvalSubjectName,
                approvalAmount, approvalSupplierName, matchStatus, matchMethod,
                matchDistance, amountStatus, differenceAmount, exceptionType, errorDetail,
                riskLevel, missingFields, amountFormatErrors, codeFormatError,
                requiredFieldsStatus, amountFormatStatus, categoryAttributionStatus,
                categorySuggestion, projectCodeStatus, projectDepartmentStatus,
                budgetYearValidityStatus, abnormalReasons, createdTime,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Compatibility constructor used by the original material-review tests and adapters.
     */
    public BudgetReviewItem(
            UUID id,
            UUID taskId,
            Integer rowNumber,
            String subjectName,
            BigDecimal declaredAmount,
            String approvalSubjectName,
            BigDecimal approvalAmount,
            String matchStatus,
            String matchMethod,
            Integer matchDistance,
            String amountStatus,
            BigDecimal differenceAmount,
            String exceptionType,
            String errorDetail,
            Instant createdTime) {
        this(
                id, taskId, rowNumber, null, subjectName, declaredAmount, null, null,
                null, approvalSubjectName, approvalAmount, null,
                matchStatus, matchMethod, matchDistance, amountStatus,
                differenceAmount, exceptionType, errorDetail,
                "INFO", null, null, null,
                "NOT_CHECKED", "NOT_CHECKED", "NOT_CHECKED", null,
                "NOT_CHECKED", "NOT_CHECKED", "NOT_CHECKED",
                List.<String>of(), createdTime, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Returns a copy with the non-deterministic AI hint attached.
     * AI output is deliberately kept separate from deterministic review facts.
     */
    public BudgetReviewItem withAiAnalysis(String suggestedCategory, String hint) {
        return withAiAnalysis(suggestedCategory, hint, null, null);
    }

    /**
     * Returns a copy with the non-deterministic AI hint and call provenance attached.
     * AI output is deliberately kept separate from deterministic review facts.
     */
    public BudgetReviewItem withAiAnalysis(
            String suggestedCategory, String hint, String model, Long durationMs) {
        return withAiAnalysis(suggestedCategory, hint, model, durationMs,
                null, null, null, null, null);
    }

    public BudgetReviewItem withAiAnalysis(
            String suggestedCategory,
            String hint,
            String model,
            Long durationMs,
            Double confidence,
            String judgment,
            Double similarityScore,
            String enhancedDescription,
            String suggestion) {
        return new BudgetReviewItem(
                id, taskId, rowNumber, subjectCode, subjectName, declaredAmount,
                supplierName, projectCode, approvalSubjectCode, approvalSubjectName,
                approvalAmount, approvalSupplierName, matchStatus, matchMethod,
                matchDistance, amountStatus, differenceAmount, exceptionType, errorDetail,
                riskLevel, missingFields, amountFormatErrors, codeFormatError,
                requiredFieldsStatus, amountFormatStatus, categoryAttributionStatus,
                categorySuggestion, projectCodeStatus, projectDepartmentStatus,
                budgetYearValidityStatus, abnormalReasons, createdTime,
                suggestedCategory, hint, model, durationMs, confidence, judgment,
                similarityScore, enhancedDescription, suggestion);
    }
}
