package com.fuquan.audit.contract.application.project.factai;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.domain.project.DocumentRole;

/** Durable read/write boundary for versioned, non-authoritative AI fact extraction attempts. */
public interface ContractFactExtractionRepository {
    Claim claim(ContractFactExtractionAttempt proposed);
    Optional<ContractFactExtractionAttempt> findById(UUID attemptId);
    Optional<ContractFactExtractionAttempt> findByOperation(String actorId, String operationKey);
    Optional<ContractFactExtractionAttempt> findLatest(UUID projectId, UUID fileId, UUID parseResultId);
    Optional<ContractFactExtractionAttempt> findLatestForAuthority(UUID projectId, UUID fileId,
            UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            DocumentRole effectiveRole, Provenance provenance);
    List<ContractFactExtractionCandidate> findCandidates(UUID attemptId);
    default List<ContractFactExtractionRejection> findRejections(UUID attemptId) { return List.of(); }
    default List<ContractModelCallAudit> findModelCalls(UUID attemptId) { return List.of(); }
    void complete(ContractFactExtractionAttempt expected, List<ContractFactExtractionCandidate> candidates,
            int acceptedCount, int rejectedCount, Instant completedAt);
    default void completeV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionCandidate> candidates,
            List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> modelCalls,
            int acceptedCount, int rejectedCount, Instant completedAt) {
        complete(expected, candidates, acceptedCount, rejectedCount, completedAt);
    }
    void failV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> modelCalls,
            int rejectedCount, String reason, Instant failedAt);
    void fail(ContractFactExtractionAttempt expected, String reason, Instant failedAt);

    record Claim(boolean acquired, ContractFactExtractionAttempt attempt) {
        public Claim { java.util.Objects.requireNonNull(attempt); }
    }
}
