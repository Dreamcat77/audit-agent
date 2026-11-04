package com.fuquan.audit.contract.domain.closeout;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Seen with a handling opinion; never a finding disposition or resolution. */
public record ContractReviewFindingAcknowledgement(UUID acknowledgementId, UUID projectId,
        UUID contractId, UUID reviewRunId, UUID reviewStepId, UUID attemptId, UUID findingId,
        long findingVersionNo, String acknowledgedBy, Instant acknowledgedAt, String handlingNote,
        String requestId, String traceId, Instant createdAt, long versionNo) {
    public ContractReviewFindingAcknowledgement {
        Objects.requireNonNull(acknowledgementId); Objects.requireNonNull(projectId);
        Objects.requireNonNull(contractId); Objects.requireNonNull(reviewRunId);
        Objects.requireNonNull(reviewStepId); Objects.requireNonNull(attemptId);
        Objects.requireNonNull(findingId); Objects.requireNonNull(acknowledgedAt);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(acknowledgedBy);
        if (findingVersionNo < 0 || versionNo != 0 || acknowledgedBy.isBlank())
            throw new IllegalArgumentException("invalid acknowledgement identity");
        handlingNote = normalizeNote(handlingNote);
    }
    public static String normalizeNote(String note) {
        if (note == null) return null;
        String normalized = note.strip();
        if (normalized.codePointCount(0, normalized.length()) > 1000)
            throw new IllegalArgumentException("handling note exceeds 1000 code points");
        return normalized.isEmpty() ? null : normalized;
    }
}
