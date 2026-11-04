package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractCandidateSupportAuthorityRepository {
    List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId);

    List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateIdForUpdate(UUID projectId, UUID candidateId);

    Optional<ContractCandidateSupportAuthority> findByProjectIdAndCandidateIdAndSourceFileId(
            UUID projectId, UUID candidateId, UUID sourceFileId);

    void replaceCurrentCohort(UUID projectId, UUID candidateId, List<ContractCandidateSupportAuthority> cohort);

    void deleteCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId);

    void insertAll(List<ContractCandidateSupportAuthority> cohort);
}
