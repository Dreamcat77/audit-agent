package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementReviewWorkflowRepository implements ProcurementReviewWorkflowRepository {
    private final ConcurrentMap<UUID, Snapshot> states = new ConcurrentHashMap<>();

    @Override public void create(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        Snapshot snapshot = new Snapshot(workflow, steps);
        if (states.putIfAbsent(workflow.projectId(), snapshot) != null) {
            throw new IllegalStateException("采购审查流程已存在: " + workflow.projectId());
        }
    }

    @Override public void update(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        states.compute(workflow.projectId(), (projectId, existing) -> {
            if (existing == null) throw new IllegalStateException("采购审查流程不存在: " + projectId);
            return new Snapshot(workflow, steps);
        });
    }

    @Override public Optional<Snapshot> findByProjectId(UUID projectId) {
        return Optional.ofNullable(states.get(projectId));
    }
}
