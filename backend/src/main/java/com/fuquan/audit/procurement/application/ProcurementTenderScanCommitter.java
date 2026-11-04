package com.fuquan.audit.procurement.application;

import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** F10 atomic result, completion marker, workflow and audit commit after AI returns. */
@Component
public class ProcurementTenderScanCommitter {
    private final ProcurementTenderFindingRepository findings;
    private final ProcurementTenderDocumentRepository documents;
    private final ProcurementReviewWorkflowService workflow;
    private final com.fuquan.audit.common.application.OperationAuditService audit;
    private final com.fuquan.audit.security.application.AuthContext auth;
    public ProcurementTenderScanCommitter(ProcurementTenderFindingRepository findings, ProcurementTenderDocumentRepository documents,
            ProcurementReviewWorkflowService workflow, com.fuquan.audit.common.application.OperationAuditService audit,
            com.fuquan.audit.security.application.AuthContext auth) {
        this.findings=findings;this.documents=documents;this.workflow=workflow;this.audit=audit;this.auth=auth;
    }
    @Transactional
    public void commit(UUID projectId, UUID sourceId, List<ProcurementTenderFinding> result, String aiStatus) {
        workflow.lockForSnapshot(projectId);
        var d=documents.findById(sourceId).filter(s->s.projectId().equals(projectId)).orElseThrow();
        findings.replaceForReview(projectId,sourceId,result);
        if(d.parseEngine()!=null && d.parseEngine().startsWith("MATERIAL_")) {
            String engine=d.parseEngine().split("_REVIEWED_")[0]+"_REVIEWED_"+aiStatus;
            documents.update(new ProcurementTenderDocument(d.id(),d.projectId(),d.fileName(),d.fileExtension(),d.contentType(),
                    d.fileSize(),d.sha256(),d.storageKey(),d.status(),d.parseMethod(),engine,d.parseConfidence(),d.pageCount(),
                    d.parsedContentJson(),d.parseError(),d.createdBy(),d.createdTime(),java.time.Instant.now()));
        }
        boolean complete = ProcurementTenderRuleScanService.AiStatus.AVAILABLE.name().equals(aiStatus);
        workflow.recordTenderReview(projectId, (complete ? "已完成材料规则与 AI 审查" : "规则结果已保存，AI 语义审查尚未完成")
                + "，共 " + result.size() + " 条需关注内容");
        audit.success("PROCUREMENT_TENDER_ANALYSIS",auth.currentUser().orElseThrow(),"PROCUREMENT_MATERIAL",sourceId.toString(),
                d.fileName(),null,Map.of("aiStatus",aiStatus,"findingCount",result.size()),"保存采购材料合规审查结果");
    }
}
