package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ContractCandidate;
import java.util.Objects;

public record ContractCandidateMaterializationResult(
        ContractCandidate candidate,
        ContractCandidateMaterializationStatus status) {
    public ContractCandidateMaterializationResult {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == ContractCandidateMaterializationStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("materialized result cannot be NOT_APPLICABLE");
        }
    }
}
