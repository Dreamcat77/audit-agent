package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractCandidateConfirmationRepository implements ContractCandidateConfirmationRepository {
    private final Map<UUID, ContractCandidateConfirmation> byId = new HashMap<>();

    @Override public synchronized void insert(ContractCandidateConfirmation value) {
        if (byId.containsKey(value.confirmationId()) || byId.values().stream().anyMatch(existing ->
                existing.candidateId().equals(value.candidateId())
                        && existing.resultCandidateVersionNo() == value.resultCandidateVersionNo())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate confirmation stream conflict");
        }
        byId.put(value.confirmationId(), value);
    }

    @Override public synchronized Optional<ContractCandidateConfirmation> findByProjectAndCandidateAndId(
            UUID projectId, UUID candidateId, UUID confirmationId) {
        return Optional.ofNullable(byId.get(confirmationId)).filter(value -> value.projectId().equals(projectId)
                && value.candidateId().equals(candidateId));
    }

    @Override public synchronized Optional<ContractCandidateConfirmation> findLatestByProjectAndCandidate(
            UUID projectId, UUID candidateId) {
        return byId.values().stream().filter(value -> value.projectId().equals(projectId)
                && value.candidateId().equals(candidateId))
                .max(Comparator.comparingLong(ContractCandidateConfirmation::resultCandidateVersionNo)
                        .thenComparing(ContractCandidateConfirmation::confirmationId));
    }
}
