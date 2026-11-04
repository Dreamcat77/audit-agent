package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.Objects;

/** Durable structured projection; evidence remains in its own append-only stream. */
public record ProjectFileRoleCandidateProjection(
        DocumentRole role,
        BigDecimal score,
        BigDecimal confidence,
        String reasonCode) {

    public ProjectFileRoleCandidateProjection {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(score, "score must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        if (role == DocumentRole.UNKNOWN) throw new IllegalArgumentException("UNKNOWN is not a positive candidate");
        if (score.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("score must be positive");
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode must not be blank");
    }
}
