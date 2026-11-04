package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectContractCommandPort;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.reviewrun.port.ProjectContractSnapshotLockPort;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectContractAdapter implements ProjectContractQueryPort,
        ProjectContractCommandPort, ProjectContractSnapshotLockPort {
    private final ConcurrentMap<UUID, ProjectContract> byContract = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProjectContract> byCandidate = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ProjectContract association) {
        if (byContract.containsKey(association.contractId()) || byCandidate.containsKey(association.candidateId())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project contract association conflict");
        }
        byContract.put(association.contractId(), association);
        byCandidate.put(association.candidateId(), association);
    }

    @Override
    public List<ProjectContract> findByProjectId(UUID projectId) {
        return byContract.values().stream().filter(value -> value.projectId().equals(projectId))
                .sorted(Comparator.comparing(ProjectContract::createdAt).thenComparing(ProjectContract::contractId))
                .toList();
    }

    @Override
    public Optional<ProjectContract> findByContractId(UUID contractId) {
        return Optional.ofNullable(byContract.get(contractId));
    }

    @Override
    public Optional<ProjectContract> findByContractIdForUpdate(UUID contractId) {
        return findByContractId(contractId);
    }

    @Override
    public Optional<ProjectContract> findByCandidateId(UUID candidateId) {
        return Optional.ofNullable(byCandidate.get(candidateId));
    }
}
