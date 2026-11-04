package com.fuquan.audit.procurement.api;

import com.fuquan.audit.blacklist.application.BlacklistBatchCheckService;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.procurement.application.ProcurementReviewBlacklistService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/review-workflow/blacklist-check")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementReviewBlacklistController {
    private final ProcurementReviewBlacklistService service;
    public ProcurementReviewBlacklistController(ProcurementReviewBlacklistService service) { this.service = service; }

    @PostMapping
    public ApiResponse<BatchCheckResponse> check(@PathVariable UUID projectId) {
        return ApiResponse.success(BatchCheckResponse.from(service.check(projectId)));
    }

    public record BatchCheckResponse(String batchId, int totalCount, int hitCount, int missCount,
                                     java.util.List<BlacklistBatchCheckService.Hit> hits,
                                     java.util.List<BlacklistBatchCheckService.Miss> misses) {
        static BatchCheckResponse from(BlacklistBatchCheckService.BatchResult result) {
            return new BatchCheckResponse(result.batchId(), result.totalCount(), result.hitCount(), result.missCount(),
                    result.hits(), result.misses());
        }
    }
}
