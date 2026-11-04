package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','AUDITOR')")
public class ContractReviewStepDiagnosticsController {
    private final ContractReviewStepDiagnosticsService diagnostics;
    private final AuthContext auth;

    public ContractReviewStepDiagnosticsController(ContractReviewStepDiagnosticsService diagnostics,
            AuthContext auth) {
        this.diagnostics = diagnostics;
        this.auth = auth;
    }

    @GetMapping("/diagnostics")
    public ApiResponse<ContractReviewStepDiagnosticsService.Diagnostics> get(
            @PathVariable UUID projectId, @PathVariable UUID contractId,
            @PathVariable UUID runId, @PathVariable UUID stepId) {
        String actorId = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
        return ApiResponse.success(diagnostics.get(projectId, contractId, runId, stepId, actorId));
    }
}
