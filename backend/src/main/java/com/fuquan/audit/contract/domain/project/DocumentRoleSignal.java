package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Immutable, parse-bound evidence emitted by deterministic document-role rules. */
public record DocumentRoleSignal(
        DocumentRole role,
        SignalType signalType,
        String normalizedValue,
        String ruleCode,
        BigDecimal weight,
        UUID parseResultId,
        int charStart,
        int charEnd,
        String sourceLocator,
        String textHash,
        String excerpt,
        String extractorVersion) {

    public enum SignalType {
        TITLE,
        FRONT_MATTER,
        KEY_PHRASE,
        HEADING,
        STRUCTURE
    }

    public DocumentRoleSignal {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(signalType, "signalType must not be null");
        normalizedValue = requireNotBlank(normalizedValue, "normalizedValue");
        ruleCode = requireNotBlank(ruleCode, "ruleCode");
        Objects.requireNonNull(weight, "weight must not be null");
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        sourceLocator = requireNotBlank(sourceLocator, "sourceLocator");
        textHash = requireNotBlank(textHash, "textHash");
        excerpt = requireNotBlank(excerpt, "excerpt");
        extractorVersion = requireNotBlank(extractorVersion, "extractorVersion");
        if (weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        if (charStart < 0 || charEnd <= charStart) {
            throw new IllegalArgumentException("invalid canonical character range");
        }
        String expectedLocator = "parse-result:%s:canonical-codepoint:%d-%d"
                .formatted(parseResultId, charStart, charEnd);
        if (!expectedLocator.equals(sourceLocator)) {
            throw new IllegalArgumentException("sourceLocator does not match canonical character range");
        }
        if (!textHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("textHash must be a lowercase SHA-256");
        }
        if (excerpt.codePointCount(0, excerpt.length()) > 160) {
            throw new IllegalArgumentException("excerpt exceeds 160 code points");
        }
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
