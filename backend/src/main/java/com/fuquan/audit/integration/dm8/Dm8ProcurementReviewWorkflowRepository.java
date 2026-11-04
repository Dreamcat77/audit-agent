package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementReviewWorkflowRepository implements ProcurementReviewWorkflowRepository {
    private final ProcurementReviewWorkflowMapper mapper;
    public Dm8ProcurementReviewWorkflowRepository(ProcurementReviewWorkflowMapper mapper) { this.mapper = mapper; }

    @Override @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockProject(UUID projectId) {
        if (mapper.lockProject(projectId.toString()) == null) throw new IllegalStateException("采购项目不存在");
    }

    @Override @Transactional
    public void create(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        mapper.insertWorkflow(workflowRow(workflow));
        mapper.insertSteps(steps.stream().map(step -> stepRow(workflow.id(), step)).toList());
    }

    @Override @Transactional
    public void update(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        if (mapper.updateWorkflow(workflowRow(workflow)) != 1) throw new IllegalStateException("采购审查流程不存在: " + workflow.projectId());
        for (ProcurementReviewStep step : steps) {
            if (mapper.updateStep(stepRow(workflow.id(), step)) != 1) throw new IllegalStateException("采购审查步骤不存在: " + step.stepCode());
        }
    }

    @Override @Transactional(readOnly = true)
    public Optional<Snapshot> findByProjectId(UUID projectId) {
        ProcurementReviewWorkflowPersistenceRow workflow = mapper.findWorkflowByProjectId(projectId.toString());
        if (workflow == null) return Optional.empty();
        List<ProcurementReviewStep> steps = mapper.findStepsByWorkflowId(workflow.getId()).stream().map(this::step).toList();
        return Optional.of(new Snapshot(workflow(workflow), steps));
    }

    private static ProcurementReviewWorkflowPersistenceRow workflowRow(ProcurementReviewWorkflow value) {
        ProcurementReviewWorkflowPersistenceRow row = new ProcurementReviewWorkflowPersistenceRow();
        row.setId(value.id().toString()); row.setProjectId(value.projectId().toString()); row.setCurrentStepOrder(value.currentStepOrder()); row.setStatus(value.status());
        row.setCreatedTime(LocalDateTime.ofInstant(value.createdTime(), ZoneOffset.UTC)); row.setUpdatedTime(LocalDateTime.ofInstant(value.updatedTime(), ZoneOffset.UTC)); return row;
    }
    private static ProcurementReviewStepPersistenceRow stepRow(UUID workflowId, ProcurementReviewStep value) {
        ProcurementReviewStepPersistenceRow row = new ProcurementReviewStepPersistenceRow();
        row.setWorkflowId(workflowId.toString()); row.setStepCode(value.stepCode()); row.setStepOrder(value.stepOrder()); row.setStepName(value.stepName()); row.setStatus(value.status());
        row.setCompletedTime(value.completedTime() == null ? null : LocalDateTime.ofInstant(value.completedTime(), ZoneOffset.UTC)); row.setResultSummary(value.resultSummary()); return row;
    }
    private static ProcurementReviewWorkflow workflow(ProcurementReviewWorkflowPersistenceRow row) {
        return new ProcurementReviewWorkflow(UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()), row.getCurrentStepOrder(), row.getStatus(), row.getCreatedTime().toInstant(ZoneOffset.UTC), row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
    private ProcurementReviewStep step(ProcurementReviewStepPersistenceRow row) {
        return new ProcurementReviewStep(row.getStepCode(), row.getStepOrder(), row.getStepName(), row.getStatus(), row.getCompletedTime() == null ? null : row.getCompletedTime().toInstant(ZoneOffset.UTC), row.getResultSummary());
    }
}
