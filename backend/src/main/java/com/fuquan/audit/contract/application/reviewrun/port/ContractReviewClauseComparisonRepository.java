package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import java.util.List;
import java.util.UUID;

public interface ContractReviewClauseComparisonRepository {
    void insert(ContractReviewClauseComparison comparison);
    List<ContractReviewClauseComparison> findByAttemptId(UUID attemptId);
    List<ContractReviewClauseComparison> findByRunId(UUID runId);
}
