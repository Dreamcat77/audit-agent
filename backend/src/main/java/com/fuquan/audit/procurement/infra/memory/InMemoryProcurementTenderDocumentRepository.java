package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementTenderDocumentRepository implements ProcurementTenderDocumentRepository {
    private final ConcurrentMap<UUID, ProcurementTenderDocument> documents = new ConcurrentHashMap<>();

    @Override
    public void save(ProcurementTenderDocument document) {
        documents.put(document.id(), document);
    }

    @Override
    public void update(ProcurementTenderDocument document) {
        documents.put(document.id(), document);
    }

    @Override
    public Optional<ProcurementTenderDocument> findById(UUID id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public Optional<ProcurementTenderDocument> findLatestByProjectId(UUID projectId) {
        return documents.values().stream()
                .filter(document -> document.projectId().equals(projectId))
                .max(java.util.Comparator.comparing(ProcurementTenderDocument::createdTime)
                        .thenComparing(ProcurementTenderDocument::id));
    }

    @Override
    public java.util.List<ProcurementTenderDocument> findByProjectId(UUID projectId) {
        return documents.values().stream().filter(d -> d.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(ProcurementTenderDocument::createdTime).reversed()
                        .thenComparing(ProcurementTenderDocument::id)).limit(201).toList();
    }
}
