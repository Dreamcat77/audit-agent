package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** F06 short atomic result/workflow/business-audit commit, without Excel or AI work in a transaction. */
@Service
public class ProcurementQuoteCommitter {
    private final ProcurementQuoteCheckRepository repository;
    private final ProcurementReviewWorkflowService workflow;
    private final OperationAuditService audit;
    public ProcurementQuoteCommitter(ProcurementQuoteCheckRepository repository,
            ProcurementReviewWorkflowService workflow, OperationAuditService audit) {
        this.repository=repository; this.workflow=workflow; this.audit=audit;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void commit(ProcurementQuoteCheck result, CurrentUser user) {
        workflow.start(UUID.fromString(result.batchId()));
        repository.save(result);
        workflow.recordQuoteCheck(UUID.fromString(result.batchId()), "已完成三方询比价、超预算及报价规律分析");
        audit.success("PROCUREMENT_QUOTE_ANALYSIS", user, "PROCUREMENT_QUOTE", result.id().toString(),
                result.batchId(), null, Map.of("riskLevel",result.riskLevel(),"supplierCount",result.quotes().size()),
                "保存三方询比价分析与价格建议");
    }
}
