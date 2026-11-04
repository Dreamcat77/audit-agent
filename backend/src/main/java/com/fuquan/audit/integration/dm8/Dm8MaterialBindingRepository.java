package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.application.port.MaterialBindingRepository;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
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
public class Dm8MaterialBindingRepository implements MaterialBindingRepository {
    private final MaterialBindingMapper mapper;

    public Dm8MaterialBindingRepository(MaterialBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(MaterialBinding binding) {
        try {
            if (mapper.insert(toRow(binding)) != 1) {
                throw conflict("material binding insert did not affect one row");
            }
        } catch (DuplicateKeyException exception) {
            throw new ContractReviewConcurrencyException("material slot already exists", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MaterialBinding> findBySessionAndSlot(UUID sessionId, String slotKey) {
        return Optional.ofNullable(mapper.findBySessionAndSlot(sessionId.toString(), slotKey)).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaterialBinding> findBySessionId(UUID sessionId) {
        return mapper.findBySessionId(sessionId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MaterialBinding bind(
            UUID id, UUID expectedDocumentId, boolean expectedActive, long expectedVersion,
            UUID newDocumentId, Instant updatedAt) {
        int affected = mapper.bind(
                id.toString(), expectedDocumentId.toString(), expectedActive ? 1 : 0,
                expectedVersion, newDocumentId.toString(), utc(updatedAt));
        if (affected != 1) {
            throw conflict("material binding state or version conflict");
        }
        return require(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MaterialBinding deactivate(
            UUID id, UUID expectedDocumentId, long expectedVersion, Instant updatedAt) {
        if (mapper.deactivate(
                id.toString(), expectedDocumentId.toString(), expectedVersion, utc(updatedAt)) != 1) {
            throw conflict("material binding state or version conflict");
        }
        return require(id);
    }

    private MaterialBinding require(UUID id) {
        MaterialBindingPersistenceRow row = mapper.findById(id.toString());
        if (row == null) {
            throw new IllegalStateException("updated material binding is missing");
        }
        return toDomain(row);
    }

    private MaterialBindingPersistenceRow toRow(MaterialBinding value) {
        MaterialBindingPersistenceRow row = new MaterialBindingPersistenceRow();
        row.setId(value.id().toString());
        row.setSessionId(value.sessionId().toString());
        row.setSlotKey(value.slotKey());
        row.setDocumentKind(value.documentKind().name());
        row.setBoundDocumentId(value.boundDocumentId().toString());
        row.setActiveFlag(value.active() ? 1 : 0);
        row.setCreatedAt(utc(value.createdAt()));
        row.setUpdatedAt(utc(value.updatedAt()));
        row.setVersionNo(value.versionNo());
        return row;
    }

    private MaterialBinding toDomain(MaterialBindingPersistenceRow row) {
        return new MaterialBinding(
                UUID.fromString(row.getId()), UUID.fromString(row.getSessionId()), row.getSlotKey(),
                ReviewDocumentKind.valueOf(row.getDocumentKind()), UUID.fromString(row.getBoundDocumentId()),
                row.getActiveFlag() == 1, instant(row.getCreatedAt()), instant(row.getUpdatedAt()), row.getVersionNo());
    }

    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private ContractReviewConcurrencyException conflict(String message) {
        return new ContractReviewConcurrencyException(message);
    }
}
