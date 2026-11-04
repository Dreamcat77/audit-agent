package com.fuquan.audit.contract.application.project.factai;

import java.util.List;
import java.util.UUID;

/** Durable, credential-free model transport audit. */
public interface ContractModelCallAuditRepository {
    void insertAll(List<ContractModelCallAudit> calls);
    List<ContractModelCallAudit> findByReviewStepAttempt(UUID attemptId);
}
