package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.RejectionStage;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe durable summary of one provider row rejected before publication. */
public record ContractFactExtractionRejection(UUID rejectionId, UUID attemptId, int attemptNo,
        Scope scope, int providerOrdinal, RejectionStage stage, String reasonCode, String safeShapeHash,
        Instant createdAt) {
    public ContractFactExtractionRejection {
        Objects.requireNonNull(rejectionId); Objects.requireNonNull(attemptId);
        Objects.requireNonNull(scope); Objects.requireNonNull(stage); Objects.requireNonNull(createdAt);
        if (attemptNo <= 0 || providerOrdinal <= 0) {
            throw new IllegalArgumentException("attemptNo and providerOrdinal must be positive");
        }
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid rejection reason");
        }
        if (safeShapeHash == null || !safeShapeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid rejection shape hash");
        }
    }
}
