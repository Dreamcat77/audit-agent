package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Insert-only signal evidence anchored to one immutable parse result. */
public record ProjectFileRoleEvidence(
        UUID evidenceId,
        UUID assessmentId,
        UUID projectId,
        UUID fileId,
        String fileSha256,
        UUID parseResultId,
        int parseVersion,
        DocumentRole role,
        DocumentRoleSignal.SignalType signalType,
        String normalizedValue,
        String ruleCode,
        BigDecimal weight,
        String sourceLocator,
        int charStart,
        int charEnd,
        String textHash,
        String boundedExcerpt,
        String extractorVersion,
        Instant createdAt) {

    public ProjectFileRoleEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(assessmentId, "assessmentId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        requireSha(fileSha256, "fileSha256");
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        if (parseVersion <= 0) throw new IllegalArgumentException("parseVersion must be positive");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(signalType, "signalType must not be null");
        require(normalizedValue, "normalizedValue");
        require(ruleCode, "ruleCode");
        Objects.requireNonNull(weight, "weight must not be null");
        if (weight.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("weight must be positive");
        if (charStart < 0 || charEnd <= charStart) throw new IllegalArgumentException("invalid character range");
        String expected = "parse-result:%s:canonical-codepoint:%d-%d".formatted(parseResultId, charStart, charEnd);
        if (!expected.equals(sourceLocator)) throw new IllegalArgumentException("sourceLocator does not match parse range");
        requireSha(textHash, "textHash");
        require(boundedExcerpt, "boundedExcerpt");
        if (boundedExcerpt.codePointCount(0, boundedExcerpt.length()) > 160) {
            throw new IllegalArgumentException("boundedExcerpt exceeds 160 code points");
        }
        require(extractorVersion, "extractorVersion");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean sameSignalFact(ProjectFileRoleEvidence other) {
        return assessmentId.equals(other.assessmentId) && projectId.equals(other.projectId)
                && fileId.equals(other.fileId) && fileSha256.equals(other.fileSha256)
                && parseResultId.equals(other.parseResultId) && parseVersion == other.parseVersion
                && role == other.role && signalType == other.signalType
                && normalizedValue.equals(other.normalizedValue) && ruleCode.equals(other.ruleCode)
                && weight.compareTo(other.weight) == 0 && sourceLocator.equals(other.sourceLocator)
                && charStart == other.charStart && charEnd == other.charEnd && textHash.equals(other.textHash)
                && boundedExcerpt.equals(other.boundedExcerpt) && extractorVersion.equals(other.extractorVersion)
                && createdAt.equals(other.createdAt);
    }

    public boolean sameIdentityFact(ProjectFileRoleEvidence other) {
        return evidenceId.equals(other.evidenceId) && sameSignalFact(other);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }
}
