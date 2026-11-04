package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import java.util.List;
import java.util.UUID;

public interface ProcurementTenderFindingRepository {
    void replaceForReview(UUID projectId, UUID reviewId, List<ProcurementTenderFinding> findings);

    List<ProcurementTenderFinding> findByReviewId(UUID projectId, UUID reviewId);
}
