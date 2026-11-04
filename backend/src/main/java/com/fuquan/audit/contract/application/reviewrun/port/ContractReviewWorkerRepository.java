package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Focused durable work discovery and lease CAS, backed by the existing attempt table. */
public interface ContractReviewWorkerRepository {
    enum LeaseCondition { PENDING, ACTIVE, EXPIRED }
    record Candidate(UUID runId, UUID stepId, UUID attemptId, long versionNo) { }
    /** Identity only: no browser Session, credential or reconstructed authorization role. */
    record BusinessInitiator(String userId, String username, String displayName) { }
    Optional<BusinessInitiator> findBusinessInitiator(String userId);
    /** Eligible deterministic work precedes semantic calls, with priority applied before the row limit. */
    List<Candidate> findClaimCandidates(int limit, Instant now);
    List<Candidate> findExpiredLeases(int limit, Instant now);
    ContractReviewStepAttempt compareAndSetAttempt(ContractReviewStepAttempt expected,
            ContractReviewStepAttempt next, LeaseCondition condition, Instant now);
    void advanceRetry(ContractReviewStep expected, ContractReviewStep next,
            ContractReviewRun expectedRun, ContractReviewRun resumedRun);
}
