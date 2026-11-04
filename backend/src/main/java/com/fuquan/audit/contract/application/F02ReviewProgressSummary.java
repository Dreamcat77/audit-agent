package com.fuquan.audit.contract.application;

import java.util.Objects;

/** Compact durable F02 progress used by contract-owned task listings. */
public record F02ReviewProgressSummary(
        int current,
        int total,
        boolean complete,
        boolean pendingAcknowledgement,
        long riskCount,
        boolean materialInsufficient,
        String riskSummary) {

    public F02ReviewProgressSummary {
        if (current < 0 || total < 0 || current > total || riskCount < 0) {
            throw new IllegalArgumentException("invalid F02 progress summary");
        }
        Objects.requireNonNull(riskSummary, "riskSummary must not be null");
    }

    public F02ReviewProgressSummary(
            int current,
            int total,
            boolean complete,
            boolean pendingAcknowledgement,
            long riskCount,
            String riskSummary) {
        this(current, total, complete, pendingAcknowledgement, riskCount, false, riskSummary);
    }
}
