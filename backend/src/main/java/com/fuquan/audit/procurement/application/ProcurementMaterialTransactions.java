package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short commits only. Parsing, OCR and AI run outside these project-row locks. */
@Component
public class ProcurementMaterialTransactions {
    private final ProcurementTenderDocumentRepository sources;
    private final ProcurementCollusionRepository bidders;
    private final ProcurementReviewWorkflowRepository workflows;
    private final OperationAuditPort audit;
    private final Clock clock;
    public ProcurementMaterialTransactions(ProcurementTenderDocumentRepository sources, ProcurementCollusionRepository bidders,
            ProcurementReviewWorkflowRepository workflows, OperationAuditPort audit, Clock clock) {
        this.sources = sources; this.bidders = bidders; this.workflows = workflows; this.audit = audit; this.clock = clock;
    }
    @Transactional
    public ProcurementTenderDocument register(ProcurementTenderDocument source) {
        workflows.lockProject(source.projectId());
        var existing = sources.findById(source.id());
        if (existing.isPresent()) return existing.get();
        invalidateReport(source.projectId());
        sources.save(source);
        record(source.projectId(), source.createdBy(), "MATERIAL_UPLOAD", source.id().toString());
        return source;
    }
    @Transactional
    public ProcurementCollusionDocument registerBidder(ProcurementCollusionDocument bidder, String actor) {
        workflows.lockProject(bidder.projectId());
        var existing = bidders.findDocuments(bidder.projectId()).stream().filter(d -> d.id().equals(bidder.id())).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().bidderName().equals(bidder.bidderName())) throw new com.fuquan.audit.common.exception.BusinessException(
                    com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT, "此材料已有其他投标人归属，请先核实");
            return existing.get();
        }
        bidders.saveDocument(bidder);
        invalidateReport(bidder.projectId());
        record(bidder.projectId(), actor, "BIDDER_MATERIAL", bidder.id() + ":" + bidder.bidderName());
        return bidder;
    }
    @Transactional
    public void prepare(UUID projectId) {
        workflows.lockProject(projectId);
        invalidateReport(projectId);
    }
    private void invalidateReport(UUID projectId) {
        workflows.findByProjectId(projectId).ifPresent(snapshot -> {
            if (snapshot.steps().stream().anyMatch(s -> s.stepCode().equals("APPROVAL") && s.status().equals("COMPLETED")))
                throw new com.fuquan.audit.common.exception.BusinessException(
                        com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT, "项目已提交审批，不能替换审查材料");
            var steps = snapshot.steps().stream().map(s -> java.util.Set.of("REPORT", "APPROVAL").contains(s.stepCode())
                    ? new ProcurementReviewStep(s.stepCode(), s.stepOrder(), s.stepName(), "PENDING", null, null) : s).toList();
            var w = snapshot.workflow();
            workflows.update(new ProcurementReviewWorkflow(w.id(), w.projectId(), 5, "IN_PROGRESS", w.createdTime(), clock.instant()), steps);
        });
    }
    private void record(UUID projectId, String actor, String action, String detail) {
        audit.append(new OperationAuditEvent("BUSINESS_OPERATION", "PROCUREMENT", action, actor, null,
                "PROCUREMENT_PROJECT", projectId.toString(), null, OperationAuditResultStatus.SUCCESS,
                "RECORDED", "保存采购审查材料", detail, null, null, null, clock.instant()));
    }
}
