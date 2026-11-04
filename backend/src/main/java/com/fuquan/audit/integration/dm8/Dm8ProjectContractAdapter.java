package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectContractCommandPort;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.reviewrun.port.ProjectContractSnapshotLockPort;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectContractAdapter implements ProjectContractQueryPort,
        ProjectContractCommandPort, ProjectContractSnapshotLockPort {
    private final ProjectContractMapper mapper;

    public Dm8ProjectContractAdapter(ProjectContractMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ProjectContract association) {
        try {
            mapper.insert(row(association));
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project contract association conflict");
        }
    }

    @Override
    public List<ProjectContract> findByProjectId(UUID projectId) {
        return mapper.findByProjectId(projectId.toString()).stream().map(this::domain).toList();
    }

    @Override
    public Optional<ProjectContract> findByContractId(UUID contractId) {
        return Optional.ofNullable(mapper.findByContractId(contractId.toString())).map(this::domain);
    }

    @Override
    public Optional<ProjectContract> findByContractIdForUpdate(UUID contractId) {
        return Optional.ofNullable(mapper.findByContractIdForUpdate(contractId.toString())).map(this::domain);
    }

    @Override
    public Optional<ProjectContract> findByCandidateId(UUID candidateId) {
        return Optional.ofNullable(mapper.findByCandidateId(candidateId.toString())).map(this::domain);
    }

    private ProjectContractPersistenceRow row(ProjectContract value) {
        return new ProjectContractPersistenceRow(value.projectId().toString(), value.contractId().toString(),
                value.candidateId().toString(), value.createdBy(),
                java.time.LocalDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC));
    }

    private ProjectContract domain(ProjectContractPersistenceRow row) {
        return new ProjectContract(UUID.fromString(row.projectId()), UUID.fromString(row.contractId()),
                UUID.fromString(row.candidateId()), row.createdBy(), row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
