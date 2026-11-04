package com.fuquan.audit.contract.application;

import java.time.Duration;
import java.util.Objects;

/** Application-owned upload and abandoned-pending recovery policy. */
public record ReviewFilePolicy(long sizeLimit, Duration pendingRecoveryAge) {
    public ReviewFilePolicy {
        if (sizeLimit <= 0) {
            throw new IllegalArgumentException("file size limit must be positive");
        }
        Objects.requireNonNull(pendingRecoveryAge, "pending recovery age must not be null");
        if (pendingRecoveryAge.isZero() || pendingRecoveryAge.isNegative()) {
            throw new IllegalArgumentException("pending recovery age must be positive");
        }
    }
}
