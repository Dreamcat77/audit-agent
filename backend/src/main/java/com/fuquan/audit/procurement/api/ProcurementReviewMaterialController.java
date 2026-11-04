package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementReviewMaterialService;
import com.fuquan.audit.procurement.application.ProcurementTenderDocumentService;
import com.fuquan.audit.procurement.application.ProcurementTenderRuleScanService;
import com.fuquan.audit.security.application.AuthContext;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/review-materials")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementReviewMaterialController {
    private final ProcurementReviewMaterialService service;
    private final AuthContext auth;
    public ProcurementReviewMaterialController(ProcurementReviewMaterialService service, AuthContext auth) {
        this.service = service; this.auth = auth;
    }
    @PostMapping(value = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Response> upload(@PathVariable UUID projectId, @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请选择采购材料");
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        String name = file.getOriginalFilename();
        String extension = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("xls", "xlsx").contains(extension)
                && user.roles().stream().noneMatch(java.util.Set.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR")::contains)) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "当前账号没有报价分析权限");
        }
        try (var input = file.getInputStream()) {
            var result = service.upload(projectId, file.getOriginalFilename(), file.getContentType(), input,
                    user.userId());
            return ApiResponse.success(new Response(result.status(), result.message(), result.document(), result.scan(),
                    result.quote() == null ? null : ProcurementQuoteCheckController.QuoteCheckResponse.from(result.quote())));
        } catch (IOException ex) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "材料解析失败，原文件已保留，请查看材料状态及诊断日志", ex);
        }
    }
    public record Response(String status, String message, ProcurementTenderDocumentService.TenderDocumentResult document,
            ProcurementTenderRuleScanService.ScanResult scan, ProcurementQuoteCheckController.QuoteCheckResponse quote) {}
}
