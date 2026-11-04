package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractReviewStepAttemptRepository {
    void insert(ContractReviewStepAttempt attempt);
    List<ContractReviewStepAttempt> findAttemptsByRunId(UUID runId);
    default Optional<ContractReviewStepAttempt> findAttemptById(UUID attemptId) {
        throw new UnsupportedOperationException("attempt reopen not implemented");
    }
    default ContractReviewStepAttempt transition(ContractReviewStepAttempt expected,
            ContractReviewStepAttempt next) {
        throw new UnsupportedOperationException("attempt transition not implemented");
    }
    default void rollbackUncommittedRun(UUID runId) { }
}
