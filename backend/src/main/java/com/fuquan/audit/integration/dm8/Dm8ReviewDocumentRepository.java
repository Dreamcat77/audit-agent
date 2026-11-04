package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.application.port.ReviewDocumentRepository;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import com.fuquan.audit.contract.domain.ReviewDocumentStatus;
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
public class Dm8ReviewDocumentRepository implements ReviewDocumentRepository {
    private final ReviewDocumentMapper mapper;

    public Dm8ReviewDocumentRepository(ReviewDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ReviewDocument document) {
        try {
            if (mapper.insert(toRow(document)) != 1) {
                throw conflict("review document insert did not affect one row");
            }
        } catch (DuplicateKeyException exception) {
            throw new ContractReviewConcurrencyException("review document identity conflict", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDocument> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDocument> findBySessionAndId(UUID sessionId, UUID id) {
        return Optional.ofNullable(mapper.findBySessionAndId(sessionId.toString(), id.toString()))
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDocument> findBySessionId(UUID sessionId) {
        return mapper.findBySessionId(sessionId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDocument> findPendingBySessionId(UUID sessionId) {
        return mapper.findPendingBySessionId(sessionId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewDocument markAvailable(UUID id, long expectedVersion, Instant storedAt) {
        if (mapper.markAvailable(id.toString(), expectedVersion, utc(storedAt)) != 1) {
            throw conflict("review document status or version conflict");
        }
        return require(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewDocument markFailed(UUID id, long expectedVersion, Instant failedAt) {
        if (mapper.markFailed(id.toString(), expectedVersion, utc(failedAt)) != 1) {
            throw conflict("review document status or version conflict");
        }
        return require(id);
    }

    private ReviewDocument require(UUID id) {
        ReviewDocumentPersistenceRow row = mapper.findById(id.toString());
        if (row == null) {
            throw new IllegalStateException("updated review document is missing");
        }
        return toDomain(row);
    }

    private ReviewDocumentPersistenceRow toRow(ReviewDocument value) {
        ReviewDocumentPersistenceRow row = new ReviewDocumentPersistenceRow();
        row.setId(value.id().toString());
        row.setSessionId(value.sessionId().toString());
        row.setDocumentKind(value.documentKind().name());
        row.setSlotKey(value.slotKey());
        row.setOriginalFileName(value.originalFileName());
        row.setFileExtension(value.fileExtension());
        row.setContentType(value.contentType());
        row.setFileSize(value.fileSize());
        row.setSha256(value.sha256());
        row.setStorageKey(value.storageKey());
        row.setStatus(value.status().name());
        row.setReplacesDocumentId(value.replacesDocumentId() == null ? null : value.replacesDocumentId().toString());
        row.setRequestedSessionVersion(value.requestedSessionVersion());
        row.setReservedSessionVersion(value.reservedSessionVersion());
        row.setCreatedBy(value.createdBy());
        row.setCreatedAt(utc(value.createdAt()));
        row.setUpdatedAt(utc(value.updatedAt()));
        row.setStoredAt(value.storedAt() == null ? null : utc(value.storedAt()));
        row.setFailedAt(value.failedAt() == null ? null : utc(value.failedAt()));
        row.setVersionNo(value.versionNo());
        return row;
    }

    private ReviewDocument toDomain(ReviewDocumentPersistenceRow row) {
        return new ReviewDocument(
                UUID.fromString(row.getId()), UUID.fromString(row.getSessionId()),
                ReviewDocumentKind.valueOf(row.getDocumentKind()), row.getSlotKey(), row.getOriginalFileName(),
                row.getFileExtension(), row.getContentType(), row.getFileSize(), row.getSha256(),
                row.getStorageKey(), ReviewDocumentStatus.valueOf(row.getStatus()),
                row.getReplacesDocumentId() == null ? null : UUID.fromString(row.getReplacesDocumentId()),
                row.getRequestedSessionVersion(), row.getReservedSessionVersion(),
                instant(row.getCreatedAt()), instant(row.getUpdatedAt()),
                row.getStoredAt() == null ? null : instant(row.getStoredAt()),
                row.getFailedAt() == null ? null : instant(row.getFailedAt()),
                row.getCreatedBy(), row.getVersionNo());
    }

    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private ContractReviewConcurrencyException conflict(String message) {
        return new ContractReviewConcurrencyException(message);
    }
}
