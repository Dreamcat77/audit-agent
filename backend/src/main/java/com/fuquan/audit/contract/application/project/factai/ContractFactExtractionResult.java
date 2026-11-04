package com.fuquan.audit.contract.application.project.factai;

import java.util.Objects;
import java.util.UUID;

public record ContractFactExtractionResult(UUID attemptId, boolean replayed,
        String status, int acceptedCount, int rejectedCount) {
    public ContractFactExtractionResult {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        if (acceptedCount < 0 || rejectedCount < 0) throw new IllegalArgumentException("counts must be non-negative");
    }
}
