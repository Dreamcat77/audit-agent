package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus;
import com.fuquan.audit.procurement.domain.ProcurementTenderParseMethod;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementTenderDocumentRepository implements ProcurementTenderDocumentRepository {
    private final ProcurementTenderDocumentMapper mapper;

    public Dm8ProcurementTenderDocumentRepository(ProcurementTenderDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(ProcurementTenderDocument document) { mapper.insert(toRow(document)); }

    @Override
    @Transactional
    public void update(ProcurementTenderDocument document) {
        if (mapper.update(toRow(document)) != 1) {
            throw new IllegalStateException("招标文件解析记录不存在: " + document.id());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementTenderDocument> findById(UUID id) {
        ProcurementTenderDocumentPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementTenderDocument> findLatestByProjectId(UUID projectId) {
        ProcurementTenderDocumentPersistenceRow row = mapper.findLatestByProjectId(projectId.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    private static ProcurementTenderDocumentPersistenceRow toRow(ProcurementTenderDocument document) {
        ProcurementTenderDocumentPersistenceRow row = new ProcurementTenderDocumentPersistenceRow();
        row.setId(document.id().toString()); row.setProjectId(document.projectId().toString());
        row.setFileName(document.fileName()); row.setFileExtension(document.fileExtension());
        row.setContentType(document.contentType()); row.setFileSize(document.fileSize());
        row.setSha256(document.sha256()); row.setStorageKey(document.storageKey());
        row.setStatus(document.status().name());
        row.setParseMethod(document.parseMethod() == null ? null : document.parseMethod().name());
        row.setParseEngine(document.parseEngine()); row.setParseConfidence(document.parseConfidence());
        row.setPageCount(document.pageCount()); row.setParsedContentJson(document.parsedContentJson());
        row.setParseError(document.parseError()); row.setCreatedBy(document.createdBy());
        row.setCreatedTime(LocalDateTime.ofInstant(document.createdTime(), ZoneOffset.UTC));
        row.setUpdatedTime(LocalDateTime.ofInstant(document.updatedTime(), ZoneOffset.UTC));
        return row;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ProcurementTenderDocument> findByProjectId(UUID projectId) {
        return mapper.findByProjectId(projectId.toString()).stream().map(Dm8ProcurementTenderDocumentRepository::toDomain).toList();
    }

    private static ProcurementTenderDocument toDomain(ProcurementTenderDocumentPersistenceRow row) {
        return new ProcurementTenderDocument(UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()),
                row.getFileName(), row.getFileExtension(), row.getContentType(), row.getFileSize(), row.getSha256(),
                row.getStorageKey(), ProcurementTenderDocumentStatus.valueOf(row.getStatus()),
                row.getParseMethod() == null ? null : ProcurementTenderParseMethod.valueOf(row.getParseMethod()),
                row.getParseEngine(), row.getParseConfidence(), row.getPageCount(), row.getParsedContentJson(),
                row.getParseError(), row.getCreatedBy(), row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
