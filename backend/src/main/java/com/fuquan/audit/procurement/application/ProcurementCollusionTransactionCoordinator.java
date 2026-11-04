package com.fuquan.audit.procurement.application;

import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps F11 persistence and workflow advancement in one short transaction. */
@Component
public class ProcurementCollusionTransactionCoordinator {
    private final ProcurementCollusionRepository repository;
    private final ProcurementReviewWorkflowService workflowService;
    private ProcurementReviewWorkflowRepository projectLocks;
    private ProcurementQuoteCheckService quotes;
    private com.fuquan.audit.common.application.OperationAuditService audit;
    private com.fuquan.audit.security.application.AuthContext auth;

    public ProcurementCollusionTransactionCoordinator(ProcurementCollusionRepository repository,
            ProcurementReviewWorkflowService workflowService) {
        this.repository = repository;
        this.workflowService = workflowService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcurementCollusionTransactionCoordinator(ProcurementCollusionRepository repository,
            ProcurementReviewWorkflowService workflowService, ProcurementReviewWorkflowRepository projectLocks,
            ProcurementQuoteCheckService quotes, com.fuquan.audit.common.application.OperationAuditService audit,
            com.fuquan.audit.security.application.AuthContext auth) {
        this(repository, workflowService); this.projectLocks=projectLocks; this.quotes=quotes; this.audit=audit; this.auth=auth;
    }

    @Transactional
    public void saveValidated(UUID projectId, ProcurementCollusionAssessment assessment,
            ProcurementSplitPurchaseAssessment splitPurchase, String summary, java.util.Set<UUID> expectedDocuments, UUID quoteId) {
        if (projectLocks != null) {
            projectLocks.lockProject(projectId);
            var actual = ProcurementCollusionReviewService.scoringDocuments(repository.findDocuments(projectId)).stream()
                    .map(com.fuquan.audit.procurement.domain.ProcurementCollusionDocument::id).collect(java.util.stream.Collectors.toSet());
            var currentQuote = quotes.findByBatchId(projectId.toString()).map(com.fuquan.audit.procurement.domain.ProcurementQuoteCheck::id).orElse(null);
            if (!actual.equals(expectedDocuments) || !java.util.Objects.equals(quoteId, currentQuote)) throw new com.fuquan.audit.common.exception.BusinessException(
                    com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT, "分析期间材料已更新，请重新分析当前材料");
        }
        if (repository.findLatestAssessment(projectId).filter(a -> a.id().equals(assessment.id())).isPresent()) return;
        saveScore(projectId, assessment, splitPurchase, summary);
        if (audit != null) audit.success("PROCUREMENT_COLLUSION_ANALYSIS", auth.currentUser().orElseThrow(), "PROCUREMENT_PROJECT",
                projectId.toString(), null, null, java.util.Map.of("riskLevel", assessment.riskLevel(), "riskScore", assessment.riskScore(),
                        "documentIds", expectedDocuments.stream().map(UUID::toString).sorted().toList()), "保存围串标辅助分析结果");
    }

    @Transactional
    public void saveScore(UUID projectId, ProcurementCollusionAssessment assessment,
            ProcurementSplitPurchaseAssessment splitPurchase, String workflowSummary) {
        repository.saveAssessment(assessment);
        repository.saveSplitPurchaseAssessment(splitPurchase);
        workflowService.recordCollusionScore(projectId, workflowSummary);
    }
}
