package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectContract;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectContractQueryPort {
    List<ProjectContract> findByProjectId(UUID projectId);
    Optional<ProjectContract> findByContractId(UUID contractId);
    Optional<ProjectContract> findByCandidateId(UUID candidateId);
}
