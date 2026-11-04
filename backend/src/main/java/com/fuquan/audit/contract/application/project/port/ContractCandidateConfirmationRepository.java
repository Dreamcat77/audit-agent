package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import java.util.Optional;
import java.util.UUID;

public interface ContractCandidateConfirmationRepository {
    void insert(ContractCandidateConfirmation confirmation);
    Optional<ContractCandidateConfirmation> findByProjectAndCandidateAndId(
            UUID projectId, UUID candidateId, UUID confirmationId);
    Optional<ContractCandidateConfirmation> findLatestByProjectAndCandidate(UUID projectId, UUID candidateId);
}
