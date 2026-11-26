package com.fuquan.audit.integration;

import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.security.application.port.UploadOwnershipPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Uses the same durable repositories as ingestion; no second ownership database. */
@Component
public class BusinessUploadOwnershipAdapter implements UploadOwnershipPort {
    private final ProjectRepository projects;
    private final ProjectContractQueryPort contracts;
    private final BudgetMaterialJobRepository budgets;
    private final com.fuquan.audit.procurement.autocase.AuditCaseRepository procurement;
    public BusinessUploadOwnershipAdapter(ProjectRepository projects, ProjectContractQueryPort contracts,
                                          BudgetMaterialJobRepository budgets, com.fuquan.audit.procurement.autocase.AuditCaseRepository procurement) {
        this.projects=projects; this.contracts=contracts; this.budgets=budgets; this.procurement=procurement;
    }
    @Override public boolean ownsProject(UUID id,String actor) {
        return projects.findById(id).filter(p->p.createdBy().equals(actor)).isPresent();
    }
    @Override public boolean ownsContract(UUID id,UUID projectId,String actor) {
        return contracts.findByContractId(id)
                .filter(c->projectId==null||c.projectId().equals(projectId))
                .filter(c->ownsProject(c.projectId(),actor)).isPresent();
    }
    @Override public boolean ownsProcurementBatch(String batchId, String actor) {
        UUID id;
        try { id=UUID.fromString(batchId); } catch (RuntimeException invalid) { return false; }
        return procurement.find(id).filter(c -> actor.equals(c.owner())).isPresent();
    }
    @Override public boolean ownsBudgetTask(UUID id,String actor) {
        return budgets.find(id).filter(j->j.actorId().equals(actor)).isPresent();
    }
}
