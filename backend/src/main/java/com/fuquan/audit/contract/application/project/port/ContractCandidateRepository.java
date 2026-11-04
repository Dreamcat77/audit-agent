package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface ContractCandidateRepository {
    ContractCandidate insertIfAbsent(ContractCandidate candidate);
    Optional<ContractCandidate> findByProjectIdAndId(UUID projectId, UUID id);
    List<ContractCandidate> findByProjectId(UUID projectId);
    Optional<ContractCandidate> findBySourceContractFileId(UUID projectId, UUID sourceFileId);
    default Optional<ContractCandidate> findByProjectIdAndIdForUpdate(UUID projectId, UUID id) {
        return findByProjectIdAndId(projectId, id);
    }
    default Optional<ContractCandidate> findBySourceContractFileIdForUpdate(UUID projectId, UUID sourceFileId) {
        return findBySourceContractFileId(projectId, sourceFileId);
    }
    default ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
            long expectedVersion, UUID parseResultId, UUID roleDecisionId,
            String factFingerprint, Instant updatedAt) {
        throw new UnsupportedOperationException("candidate reauthorization is not supported");
    }
    default ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
            long expectedVersion, UUID parseResultId, UUID roleDecisionId,
            UUID identityDecisionId, String factFingerprint, Instant updatedAt) {
        throw new UnsupportedOperationException("identity-bound candidate reauthorization is not supported");
    }
    default ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
            long expectedVersion, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource authoritySource,
            String factFingerprint, Instant updatedAt) {
        throw new UnsupportedOperationException("candidate authority source reauthorization is not supported");
    }
    default ContractCandidate replaceDraftDerivedFactsIfVersionMatches(UUID projectId, UUID candidateId,
            long expectedVersion, ContractCandidateDerivedFacts facts, long supportAuthorityVersionNo,
            String supportAuthorityFingerprint, Instant updatedAt) {
        throw new UnsupportedOperationException("candidate derived-fact CAS is not supported");
    }
    ContractCandidate promoteIfVersionMatches(UUID projectId, UUID candidateId, long expectedVersion,
                                              UUID contractId, String promotedBy, Instant promotedAt);

    default ContractCandidate confirmDraftIfVersionMatches(UUID projectId, UUID candidateId,
            long expectedVersion, Instant confirmedAt) {
        throw new UnsupportedOperationException("candidate confirmation CAS is not supported");
    }

    default ContractCandidate promoteIfVersionMatches(UUID projectId, UUID candidateId, long expectedVersion,
            UUID contractId, UUID confirmationId, String promotedBy, Instant promotedAt) {
        throw new UnsupportedOperationException("confirmation-bound candidate promotion is not supported");
    }
}
