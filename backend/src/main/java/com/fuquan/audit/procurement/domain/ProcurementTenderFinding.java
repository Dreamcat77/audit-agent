package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次招标文件规则命中记录。 */
public record ProcurementTenderFinding(
        UUID id,
        UUID projectId,
        UUID reviewId,
        String ruleCode,
        String ruleName,
        String riskCategory,
        String riskLevel,
        String matchedText,
        String sourceLocator,
        Integer pageNumber,
        String remediationSuggestion,
        String source,
        String riskReason,
        Instant createdTime,
        String legalBasis,
        String ruleExplanation) {

    public ProcurementTenderFinding(UUID id, UUID projectId, UUID reviewId, String ruleCode,
            String ruleName, String riskCategory, String riskLevel, String matchedText,
            String sourceLocator, Integer pageNumber, String remediationSuggestion,
            Instant createdTime) {
        this(id, projectId, reviewId, ruleCode, ruleName, riskCategory, riskLevel, matchedText,
                sourceLocator, pageNumber, remediationSuggestion, "RULE", null, createdTime, null, null);
    }

    public ProcurementTenderFinding(UUID id, UUID projectId, UUID reviewId, String ruleCode,
            String ruleName, String riskCategory, String riskLevel, String matchedText,
            String sourceLocator, Integer pageNumber, String remediationSuggestion,
            String source, String riskReason, Instant createdTime) {
        this(id, projectId, reviewId, ruleCode, ruleName, riskCategory, riskLevel, matchedText,
                sourceLocator, pageNumber, remediationSuggestion, source, riskReason, createdTime, null, null);
    }

    public ProcurementTenderFinding {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        requireText(ruleCode, "ruleCode");
        requireText(ruleName, "ruleName");
        requireText(riskCategory, "riskCategory");
        requireText(riskLevel, "riskLevel");
        requireText(matchedText, "matchedText");
        requireText(sourceLocator, "sourceLocator");
        requireText(remediationSuggestion, "remediationSuggestion");
        requireText(source, "source");
        if (!source.equals("RULE") && !source.equals("AI") && !source.equals("RULE_AND_AI")) {
            throw new IllegalArgumentException("source must be RULE, AI or RULE_AND_AI");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        if (pageNumber != null && pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
