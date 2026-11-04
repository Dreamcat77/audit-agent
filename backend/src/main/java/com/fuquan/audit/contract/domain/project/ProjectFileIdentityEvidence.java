package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded comparison evidence; raw parsed documents remain on their existing authority path. */
public record ProjectFileIdentityEvidence(
        UUID evidenceId, UUID assessmentId, UUID projectId, long projectVersionNo,
        UUID fileId, String fileSha256, UUID parseResultId, int parseVersion,
        String factType, UUID extractionResultId, String leftSource, String rightSource,
        String leftNormalizedHash, String rightNormalizedHash,
        String leftBoundedValue, String rightBoundedValue,
        ProjectIdentityComparisonOutcome comparisonOutcome,
        String significanceCode, String sourceLocator, Instant createdAt) {
    public ProjectFileIdentityEvidence {
        Objects.requireNonNull(evidenceId);
        Objects.requireNonNull(assessmentId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(fileId);
        Objects.requireNonNull(parseResultId);
        Objects.requireNonNull(comparisonOutcome);
        Objects.requireNonNull(createdAt);
        if (projectVersionNo < 0 || parseVersion <= 0) throw new IllegalArgumentException("invalid binding version");
        if (fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid file SHA-256");
        if (!"PROJECT_NO".equals(factType) && !"PROJECT_NAME".equals(factType))
            throw new IllegalArgumentException("unsupported authoritative identity fact");
        required(leftSource, 40); required(rightSource, 40); required(significanceCode, 40);
        optionalHash(leftNormalizedHash); optionalHash(rightNormalizedHash);
        optionalBounded(leftBoundedValue, 160); optionalBounded(rightBoundedValue, 160);
        optionalBounded(sourceLocator, 500);
    }

    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid evidence metadata");
    }
    private static void optionalHash(String value) {
        if (value != null && !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid normalized hash");
    }
    private static void optionalBounded(String value, int max) {
        if (value != null && value.length() > max) throw new IllegalArgumentException("unbounded identity evidence value");
    }
}
