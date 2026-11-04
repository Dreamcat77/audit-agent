package com.fuquan.audit.procurement.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** F07 招标文件语义审查的 AI 出站端口；AI 只提供疑似风险，不作最终合规裁决。 */
public interface ProcurementTenderReviewAiPort {

    Result analyze(Request request);

    record Request(UUID projectId, UUID reviewId, String fullText, List<Segment> segments,
                   List<RuleFinding> ruleFindings) {
        public Request {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            if (fullText == null || fullText.isBlank()) {
                throw new IllegalArgumentException("fullText must not be blank");
            }
            segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
            if (segments.isEmpty()) throw new IllegalArgumentException("segments must not be empty");
            ruleFindings = List.copyOf(Objects.requireNonNull(ruleFindings, "ruleFindings must not be null"));
        }
    }

    record Segment(String sourceLocator, String text, Integer pageNumber, Double confidence) {
        public Segment {
            if (sourceLocator == null || sourceLocator.isBlank()) {
                throw new IllegalArgumentException("sourceLocator must not be blank");
            }
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    record RuleFinding(String ruleCode, String riskCategory, String riskLevel, String matchedText,
                       String sourceLocator, String remediationSuggestion) {
        public RuleFinding {
            requireText(ruleCode, "ruleCode");
            requireText(riskCategory, "riskCategory");
            requireText(riskLevel, "riskLevel");
            requireText(matchedText, "matchedText");
            requireText(sourceLocator, "sourceLocator");
            requireText(remediationSuggestion, "remediationSuggestion");
        }
    }

    record Result(String model, long durationMs, List<Finding> findings) {
        public Result {
            requireText(model, "model");
            if (durationMs < 0) throw new IllegalArgumentException("durationMs must be non-negative");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        }
    }

    record Finding(String matchedText, String sourceLocator, Integer pageNumber, String riskCategory,
                   String riskLevel, String rationale, String remediationSuggestion, Double confidence) {
        public Finding {
            requireText(matchedText, "matchedText");
            requireText(sourceLocator, "sourceLocator");
            requireText(riskCategory, "riskCategory");
            requireText(riskLevel, "riskLevel");
            requireText(rationale, "rationale");
            requireText(remediationSuggestion, "remediationSuggestion");
            if (confidence == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }
    }

    class TimeoutException extends RuntimeException {
        public TimeoutException(Throwable cause) { super("tender compliance AI timed out", cause); }
    }

    class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("tender compliance AI unavailable", cause); }
    }

    class InvalidResponseException extends RuntimeException {
        public InvalidResponseException(Throwable cause) { super("invalid tender compliance AI response", cause); }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
