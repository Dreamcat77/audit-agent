package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import java.util.List;
import java.util.UUID;

public interface ContractReviewEvidenceRepository {
    void insert(ContractReviewEvidence evidence);
    List<ContractReviewEvidence> findEvidenceByAttemptId(UUID attemptId);
    List<ContractReviewEvidence> findEvidenceByRunId(UUID runId);
}
