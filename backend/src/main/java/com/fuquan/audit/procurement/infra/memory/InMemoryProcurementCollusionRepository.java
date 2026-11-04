package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
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
public class InMemoryProcurementCollusionRepository implements ProcurementCollusionRepository {
    private final ConcurrentMap<UUID, ProcurementCollusionDocument> documents = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProcurementCollusionAssessment> assessments = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProcurementSplitPurchaseAssessment> splitAssessments = new ConcurrentHashMap<>();
    @Override public void saveDocument(ProcurementCollusionDocument document) { documents.put(document.id(), document); }
    @Override public List<ProcurementCollusionDocument> findDocuments(UUID projectId) { return documents.values().stream().filter(item -> item.projectId().equals(projectId)).sorted(Comparator.comparing(ProcurementCollusionDocument::createdTime)).toList(); }
    @Override public void saveAssessment(ProcurementCollusionAssessment assessment) { assessments.put(assessment.projectId(), assessment); }
    @Override public Optional<ProcurementCollusionAssessment> findLatestAssessment(UUID projectId) { return Optional.ofNullable(assessments.get(projectId)); }
    @Override public void saveSplitPurchaseAssessment(ProcurementSplitPurchaseAssessment assessment) { splitAssessments.put(assessment.projectId(), assessment); }
    @Override public Optional<ProcurementSplitPurchaseAssessment> findLatestSplitPurchaseAssessment(UUID projectId) { return Optional.ofNullable(splitAssessments.get(projectId)); }
}
