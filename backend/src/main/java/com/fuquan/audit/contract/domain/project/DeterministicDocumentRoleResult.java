package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Non-authoritative deterministic proposal; human confirmation is always required. */
public record DeterministicDocumentRoleResult(
        DocumentRole proposedRole,
        BigDecimal confidence,
        List<DocumentRoleCandidate> candidateRoles,
        boolean conflict,
        List<DocumentRoleSignal> evidence,
        DecisionStatus decisionStatus) {

    public enum DecisionStatus {
        NEEDS_CONFIRMATION
    }

    public DeterministicDocumentRoleResult {
        Objects.requireNonNull(proposedRole, "proposedRole must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        candidateRoles = List.copyOf(Objects.requireNonNull(candidateRoles, "candidateRoles must not be null"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        Objects.requireNonNull(decisionStatus, "decisionStatus must not be null");
        if (decisionStatus != DecisionStatus.NEEDS_CONFIRMATION) {
            throw new IllegalArgumentException("deterministic proposals always require confirmation");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        if (candidateRoles.isEmpty()) {
            if (proposedRole != DocumentRole.UNKNOWN || conflict || !evidence.isEmpty()
                    || confidence.compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("empty candidates require an UNKNOWN empty result");
            }
        } else if (conflict && proposedRole != DocumentRole.UNKNOWN) {
            throw new IllegalArgumentException("conflicting maxima cannot produce a proposed role");
        } else if (!conflict && proposedRole != candidateRoles.get(0).role()) {
            throw new IllegalArgumentException("proposal must be the leading candidate");
        }
    }
}
