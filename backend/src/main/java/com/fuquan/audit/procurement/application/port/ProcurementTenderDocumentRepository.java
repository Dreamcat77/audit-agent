package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import java.util.Optional;
import java.util.UUID;

public interface ProcurementTenderDocumentRepository {
    void save(ProcurementTenderDocument document);

    void update(ProcurementTenderDocument document);

    Optional<ProcurementTenderDocument> findById(UUID id);

    Optional<ProcurementTenderDocument> findLatestByProjectId(UUID projectId);

    default java.util.List<ProcurementTenderDocument> findByProjectId(UUID projectId) {
        return findLatestByProjectId(projectId).stream().toList();
    }
}
