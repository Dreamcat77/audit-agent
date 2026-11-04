package com.fuquan.audit.procurement.api;
import com.fuquan.audit.procurement.application.*;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/procurement/projects/{projectId}/review-report")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
public class ProcurementReportExportController {
    private final ProcurementReviewReportService reports;private final ProcurementReportDocument documents;private final OperationAuditService audit;private final AuthContext auth;
    public ProcurementReportExportController(ProcurementReviewReportService reports,ProcurementReportDocument documents,OperationAuditService audit,AuthContext auth){this.reports=reports;this.documents=documents;this.audit=audit;this.auth=auth;}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@PathVariable UUID projectId,@RequestParam UUID reportId)throws IOException {
        var result=reports.requireLatest(projectId);
        if(!result.metadata().id().equals(reportId))throw new BusinessException(BusinessErrorCode.CONFLICT,"报告已更新，请刷新结果后再导出");
        byte[] bytes=documents.render(result);
        audit.success("PROCUREMENT_REPORT_EXPORTED",auth.currentUser().orElseThrow(),"PROCUREMENT_REVIEW_REPORT",reportId.toString(),null,null,null,"导出采购审查参考报告");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("Procurement-Review-"+reportId+".docx").build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);
    }
}
