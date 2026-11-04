package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import java.util.Optional;
import java.util.UUID;

public interface ContractReviewRunRepository {
    void insert(ContractReviewRun run);
    Optional<ContractReviewRun> findById(UUID runId);
    Optional<ContractReviewRun> findLatestByProjectIdAndContractIdAndRequestedBy(
            UUID projectId,
            UUID contractId,
            String requestedBy);
    /** Locks one durable run row for the surrounding short write transaction. */
    Optional<ContractReviewRun> findByIdForUpdate(UUID runId);
    default ContractReviewRun transition(ContractReviewRun expected, ContractReviewRun next) {
        throw new UnsupportedOperationException("run transition not implemented");
    }
    default void rollbackUncommittedRun(UUID runId) { }
}
