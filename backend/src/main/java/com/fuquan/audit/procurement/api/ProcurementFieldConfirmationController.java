package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementFieldConfirmationService;
import com.fuquan.audit.security.application.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/agent-review/confirmations")
public class ProcurementFieldConfirmationController {
    private final ProcurementFieldConfirmationService service;
    private final AuthContext auth;

    public ProcurementFieldConfirmationController(ProcurementFieldConfirmationService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE')")
    public ApiResponse<List<ProcurementFieldConfirmationService.Confirmation>> list(@PathVariable UUID projectId) {
        return ApiResponse.success(service.find(projectId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT')")
    public ApiResponse<ProcurementFieldConfirmationService.Confirmation> confirm(
            @PathVariable UUID projectId, @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.success(service.confirm(projectId, request.fieldCode(), request.confirmationNote(),
                auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED))));
    }

    public record ConfirmRequest(@NotBlank String fieldCode,
                                 @Size(max = 1000) String confirmationNote) {}
}
