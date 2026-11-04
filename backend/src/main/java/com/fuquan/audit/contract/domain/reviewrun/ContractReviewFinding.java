package com.fuquan.audit.contract.domain.reviewrun;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Append-only non-F02 business finding owned by one exact first step attempt. */
public record ContractReviewFinding(
        UUID findingId, UUID runId, UUID stepId, UUID attemptId, int ordinal,
        String findingType, String category, String statusCode, String attentionCode,
        String riskLevel, String summary, String recommendation, int findingSchemaVersion,
        String policyVersion, ReviewFindingSource source, String modelProvider,
        String modelName, String modelRevision, String promptVersion,
        String modelSchemaVersion, Instant createdAt, long versionNo) {
    private static final Set<String> RISKS = Set.of("GREEN", "YELLOW", "RED");

    public ContractReviewFinding {
        Objects.requireNonNull(findingId); Objects.requireNonNull(runId);
        Objects.requireNonNull(stepId); Objects.requireNonNull(attemptId);
        Objects.requireNonNull(source); Objects.requireNonNull(createdAt);
        if (ordinal <= 0 || findingSchemaVersion <= 0 || versionNo < 0)
            throw new IllegalArgumentException("invalid finding versions");
        required(findingType, 128, "findingType");
        required(category, 128, "category");
        optional(statusCode, 128, "statusCode");
        optional(attentionCode, 128, "attentionCode");
        optional(riskLevel, 32, "riskLevel");
        if (riskLevel != null && !RISKS.contains(riskLevel))
            throw new IllegalArgumentException("unsupported riskLevel");
        required(summary, 1000, "summary");
        optional(recommendation, 2000, "recommendation");
        required(policyVersion, 100, "policyVersion");
        optional(modelProvider, 100, "modelProvider");
        optional(modelName, 128, "modelName");
        optional(modelRevision, 100, "modelRevision");
        optional(promptVersion, 100, "promptVersion");
        optional(modelSchemaVersion, 100, "modelSchemaVersion");
        if (source == ReviewFindingSource.DETERMINISTIC && (modelProvider != null
                || modelName != null || modelRevision != null || promptVersion != null
                || modelSchemaVersion != null))
            throw new IllegalArgumentException("deterministic finding cannot claim model provenance");
    }

    static void required(String text, int maximum, String name) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > maximum)
            throw new IllegalArgumentException("invalid " + name);
    }

    static void optional(String text, int maximum, String name) {
        if (text != null) required(text, maximum, name);
    }
}
