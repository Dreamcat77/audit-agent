package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One deterministic role candidate with its exact score and supporting evidence. */
public record DocumentRoleCandidate(
        DocumentRole role,
        BigDecimal score,
        BigDecimal confidence,
        List<DocumentRoleSignal> evidence) {

    public DocumentRoleCandidate {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(score, "score must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (role == DocumentRole.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN is not a positive role candidate");
        }
        if (score.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("score must be positive");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        if (evidence.isEmpty() || evidence.stream().anyMatch(signal -> signal.role() != role)) {
            throw new IllegalArgumentException("candidate evidence must support its role");
        }
    }
}
