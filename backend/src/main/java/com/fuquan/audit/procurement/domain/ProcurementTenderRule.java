package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** F07 招标文件关键词/正则审查规则。 */
public record ProcurementTenderRule(
        UUID id,
        String ruleCode,
        String ruleName,
        String riskCategory,
        String matchType,
        String matchContent,
        String riskLevel,
        String remediationSuggestion,
        String legalBasis,
        String ruleExplanation,
        String source,
        boolean enabled,
        Instant createdTime,
        Instant updatedTime) {

    public ProcurementTenderRule {
        Objects.requireNonNull(id, "id must not be null");
        requireText(ruleCode, "ruleCode");
        requireText(ruleName, "ruleName");
        requireText(riskCategory, "riskCategory");
        requireText(matchType, "matchType");
        requireText(matchContent, "matchContent");
        requireText(riskLevel, "riskLevel");
        requireText(remediationSuggestion, "remediationSuggestion");
        requireText(legalBasis, "legalBasis");
        requireText(ruleExplanation, "ruleExplanation");
        requireText(source, "source");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
        if (!matchType.equals("KEYWORD") && !matchType.equals("REGEX")) {
            throw new IllegalArgumentException("matchType must be KEYWORD or REGEX");
        }
        if (!riskLevel.equals("CRITICAL") && !riskLevel.equals("WARNING") && !riskLevel.equals("INFO")) {
            throw new IllegalArgumentException("riskLevel must be CRITICAL, WARNING or INFO");
        }
        if (!source.equals("BUILTIN") && !source.equals("MANUAL") && !source.equals("LEARNED")) {
            throw new IllegalArgumentException("source must be BUILTIN, MANUAL or LEARNED");
        }
    }

    /** 兼容早期测试/内置调用；正式规则应提供法规依据和规则说明。 */
    public ProcurementTenderRule(UUID id, String ruleCode, String ruleName, String riskCategory,
            String matchType, String matchContent, String riskLevel, String remediationSuggestion,
            String source, boolean enabled, Instant createdTime, Instant updatedTime) {
        this(id, ruleCode, ruleName, riskCategory, matchType, matchContent, riskLevel,
                remediationSuggestion, "未配置法规依据", "未配置规则说明", source, enabled, createdTime, updatedTime);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
