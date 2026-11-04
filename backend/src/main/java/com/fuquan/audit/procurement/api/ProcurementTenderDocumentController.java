package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementTenderDocumentService;
import com.fuquan.audit.security.application.AuthContext;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/tender-reviews")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementTenderDocumentController {
    private final ProcurementTenderDocumentService service;
    private final AuthContext authContext;

    public ProcurementTenderDocumentController(ProcurementTenderDocumentService service, AuthContext authContext) {
        this.service = service;
        this.authContext = authContext;
    }

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProcurementTenderDocumentService.TenderDocumentResult> upload(
            @PathVariable UUID projectId, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "招标文件不能为空");
        }
        try {
            String operator = authContext.currentUser().map(user -> user.userId()).orElse(null);
            return ApiResponse.success(service.upload(projectId, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), refresh, operator));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "招标文件读取失败", exception);
        }
    }

    @GetMapping("/latest")
    public ApiResponse<ProcurementTenderDocumentService.TenderDocumentResult> latest(@PathVariable UUID projectId) {
        return ApiResponse.success(service.findLatest(projectId).orElse(null));
    }
}
