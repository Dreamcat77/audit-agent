package com.fuquan.audit.procurement.application;

import com.fuquan.audit.blacklist.application.BlacklistBatchCheckService;
import com.fuquan.audit.blacklist.application.BlacklistBatchCheckService.BatchResult;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reuses the available F06 supplier list for auxiliary F03 analysis. */
@Service
public class ProcurementReviewBlacklistService {
    private final ProcurementQuoteCheckService quoteCheckService;
    private final BlacklistBatchCheckService blacklistBatchCheckService;
    private final ProcurementReviewWorkflowService workflowService;
    private com.fuquan.audit.blacklist.application.BlacklistQueryService query;

    public ProcurementReviewBlacklistService(ProcurementQuoteCheckService quoteCheckService,
            BlacklistBatchCheckService blacklistBatchCheckService,
            ProcurementReviewWorkflowService workflowService) {
        this.quoteCheckService = quoteCheckService;
        this.blacklistBatchCheckService = blacklistBatchCheckService;
        this.workflowService = workflowService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcurementReviewBlacklistService(ProcurementQuoteCheckService quoteCheckService,
            BlacklistBatchCheckService blacklistBatchCheckService, ProcurementReviewWorkflowService workflowService,
            com.fuquan.audit.blacklist.application.BlacklistQueryService query) {
        this(quoteCheckService,blacklistBatchCheckService,workflowService);this.query=query;
    }

    /** Reopen the immutable F06 blacklist eligibility snapshot and its durable alert evidence, without a new check. */
    public BatchResult findStoredResult(UUID projectId) {
        var quote=quoteCheckService.findByBatchId(projectId.toString()).orElse(null);
        if(quote==null || quote.comparison()==null || query==null) return null;
        var excluded=new java.util.HashSet<>(quote.comparison().excludedSuppliers());
        var alerts=query.findAlertPage(projectId.toString(),null,null,null,1,1000).items();
        var hits=new java.util.ArrayList<BlacklistBatchCheckService.Hit>();var misses=new java.util.ArrayList<BlacklistBatchCheckService.Miss>();
        for(var supplier:quote.quotes()) {
            if(!excluded.contains(supplier.supplierName())) {misses.add(new BlacklistBatchCheckService.Miss(supplier.rowNumber(),supplier.supplierName(),null));continue;}
            var alert=alerts.stream().filter(a->a.supplierName().equals(supplier.supplierName()))
                    .max(java.util.Comparator.comparing(com.fuquan.audit.blacklist.domain.BlacklistAlert::createdTime)).orElse(null);
            if(alert==null) return null;
            hits.add(new BlacklistBatchCheckService.Hit(supplier.rowNumber(),supplier.supplierName(),null,alert.blacklistId(),
                    alert.blacklistType(),alert.blacklistReason(),alert.matchType(),alert.matchScore(),alert.id()));
        }
        return new BatchResult(projectId.toString(),quote.quotes().size(),hits.size(),misses.size(),java.util.List.copyOf(hits),java.util.List.copyOf(misses));
    }

    @Transactional
    public BatchResult check(UUID projectId) {
        ProcurementQuoteCheck quoteCheck = quoteCheckService.requireByBatchId(projectId.toString());
        BatchResult result = blacklistBatchCheckService.check(projectId.toString(), quoteCheck.quotes().stream()
                .map(quote -> new BlacklistBatchCheckService.SupplierInput(quote.rowNumber(), quote.supplierName(),
                        null, projectId.toString())).toList());
        workflowService.recordBlacklistCheck(projectId,
                "已完成供应商黑名单比对，命中 " + result.hitCount() + " 家，未命中 " + result.missCount() + " 家");
        return result;
    }
}
