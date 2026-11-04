package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import java.util.List;
import java.util.UUID;

public interface ContractReviewFindingRepository {
    void insert(ContractReviewFinding finding);
    List<ContractReviewFinding> findFindingsByAttemptId(UUID attemptId);
    List<ContractReviewFinding> findFindingsByRunId(UUID runId);
}
