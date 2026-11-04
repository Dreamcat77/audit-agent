package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractReviewStepRepository {
    void insert(ContractReviewStep step);
    List<ContractReviewStep> findStepsByRunId(UUID runId);
    List<ContractReviewStep> findByOutputResource(String resourceType, UUID resourceId);
    default Optional<ContractReviewStep> findStepById(UUID stepId) {
        throw new UnsupportedOperationException("step reopen not implemented");
    }
    default ContractReviewStep transition(ContractReviewStep expected, ContractReviewStep next) {
        throw new UnsupportedOperationException("step transition not implemented");
    }
    default void rollbackUncommittedRun(UUID runId) { }
}
