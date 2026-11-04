package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractReviewSessionRepository implements ContractReviewSessionRepository {
    private final ContractReviewSessionMapper mapper;

    public Dm8ContractReviewSessionRepository(ContractReviewSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ContractReviewSession session) {
        try {
            if (mapper.insert(toRow(session)) != 1) {
                throw conflict("review session insert did not affect one row");
            }
        } catch (DuplicateKeyException exception) {
            throw new ContractReviewConcurrencyException("review session already exists", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractReviewSession> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractReviewSession> findByContractId(UUID contractId) {
        return mapper.findByContractId(contractId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractReviewSession advancePreparingVersion(UUID id, long expectedVersion, Instant updatedAt) {
        if (mapper.advancePreparingVersion(id.toString(), expectedVersion, utc(updatedAt)) != 1) {
            throw conflict("review session status or version conflict");
        }
        return require(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractReviewSession confirmMaterials(UUID id, long expectedVersion, Instant confirmedAt) {
        if (mapper.confirmMaterials(id.toString(), expectedVersion, utc(confirmedAt)) != 1) {
            throw conflict("review session status or version conflict");
        }
        return require(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractReviewSession softDelete(
            UUID id, long expectedVersion, String deletedBy, Instant deletedAt) {
        if (mapper.softDelete(
                id.toString(), expectedVersion, deletedBy, utc(deletedAt)) != 1) {
            throw conflict("review session delete conflict");
        }
        return require(id);
    }

    private ContractReviewSession require(UUID id) {
        ContractReviewSessionPersistenceRow row = mapper.findById(id.toString());
        if (row == null) {
            throw new IllegalStateException("updated review session is missing");
        }
        return toDomain(row);
    }

    private ContractReviewSessionPersistenceRow toRow(ContractReviewSession value) {
        ContractReviewSessionPersistenceRow row = new ContractReviewSessionPersistenceRow();
        row.setId(value.id().toString());
        row.setContractId(value.contractId().toString());
        row.setContractVersion(value.contractVersion());
        row.setName(value.name());
        row.setReviewType(value.reviewType());
        row.setStatus(value.status().name());
        row.setRemark(value.remark());
        row.setCreatedBy(value.createdBy());
        row.setCreatedAt(utc(value.createdAt()));
        row.setUpdatedAt(utc(value.updatedAt()));
        row.setMaterialsConfirmedAt(value.materialsConfirmedAt() == null ? null : utc(value.materialsConfirmedAt()));
        row.setVersionNo(value.versionNo());
        row.setDeletedAt(value.deletedAt() == null ? null : utc(value.deletedAt()));
        row.setDeletedBy(value.deletedBy());
        row.setSourceKind(value.sourceKind().name());
        row.setProjectId(value.projectId() == null ? null : value.projectId().toString());
        return row;
    }

    private ContractReviewSession toDomain(ContractReviewSessionPersistenceRow row) {
        return new ContractReviewSession(
                UUID.fromString(row.getId()), UUID.fromString(row.getContractId()), row.getContractVersion(),
                row.getName(), row.getReviewType(), ReviewSessionStatus.valueOf(row.getStatus()), row.getRemark(),
                row.getCreatedBy(), instant(row.getCreatedAt()), instant(row.getUpdatedAt()),
                row.getMaterialsConfirmedAt() == null ? null : instant(row.getMaterialsConfirmedAt()),
                row.getVersionNo(),
                row.getDeletedAt() == null ? null : instant(row.getDeletedAt()), row.getDeletedBy(),
                com.fuquan.audit.contract.domain.ReviewSessionSourceKind.valueOf(row.getSourceKind()),
                row.getProjectId() == null ? null : UUID.fromString(row.getProjectId()));
    }

    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private ContractReviewConcurrencyException conflict(String message) {
        return new ContractReviewConcurrencyException(message);
    }
}
