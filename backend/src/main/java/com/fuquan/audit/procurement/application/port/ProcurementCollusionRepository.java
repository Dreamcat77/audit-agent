package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcurementCollusionRepository {
    void saveDocument(ProcurementCollusionDocument document);
    List<ProcurementCollusionDocument> findDocuments(UUID projectId);
    void saveAssessment(ProcurementCollusionAssessment assessment);
    Optional<ProcurementCollusionAssessment> findLatestAssessment(UUID projectId);
    void saveSplitPurchaseAssessment(ProcurementSplitPurchaseAssessment assessment);
    Optional<ProcurementSplitPurchaseAssessment> findLatestSplitPurchaseAssessment(UUID projectId);
}
