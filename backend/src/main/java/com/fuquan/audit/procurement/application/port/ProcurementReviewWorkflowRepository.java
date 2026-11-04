package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcurementReviewWorkflowRepository {
    /** Serialize workflow read/modify/write, including first creation, in the caller's transaction. */
    default void lockProject(UUID projectId) { /* Local/test seam. DM8 implements a durable row lock. */ }
    void create(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps);
    void update(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps);
    Optional<Snapshot> findByProjectId(UUID projectId);

    record Snapshot(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        public Snapshot { steps = List.copyOf(steps); }
    }
}
