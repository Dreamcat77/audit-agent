package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.procurement.application.ProcurementTenderRuleScanService;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/tender-reviews")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementTenderRuleScanController {
    private final ProcurementTenderRuleScanService service;

    public ProcurementTenderRuleScanController(ProcurementTenderRuleScanService service) {
        this.service = service;
    }

    @PostMapping("/{reviewId}/scan")
    public ApiResponse<ScanResponse> scan(@PathVariable UUID projectId, @PathVariable UUID reviewId) {
        ProcurementTenderRuleScanService.ScanResult result = service.scan(projectId, reviewId);
        return ApiResponse.success(ScanResponse.from(result));
    }

    @GetMapping("/{reviewId}/findings")
    public ApiResponse<List<FindingResponse>> findings(@PathVariable UUID projectId, @PathVariable UUID reviewId) {
        return ApiResponse.success(service.findFindings(projectId, reviewId).stream()
                .map(FindingResponse::from).toList());
    }

    public record ScanResponse(UUID projectId, UUID reviewId, int findingCount, String aiStatus,
            List<FindingResponse> findings) {
        static ScanResponse from(ProcurementTenderRuleScanService.ScanResult result) {
            return new ScanResponse(result.projectId(), result.reviewId(), result.findingCount(),
                    result.aiStatus().name(),
                    result.findings().stream().map(FindingResponse::from).toList());
        }
    }

    public record FindingResponse(UUID id, UUID projectId, UUID reviewId, String ruleCode, String ruleName,
            String riskCategory, String riskLevel, String matchedText, String sourceLocator,
            Integer pageNumber, String remediationSuggestion, String source, String riskReason,
            java.time.Instant createdTime, String legalBasis, String ruleExplanation) {
        static FindingResponse from(ProcurementTenderFinding finding) {
            return new FindingResponse(finding.id(), finding.projectId(), finding.reviewId(), finding.ruleCode(),
                    finding.ruleName(), finding.riskCategory(), finding.riskLevel(), finding.matchedText(),
                    finding.sourceLocator(), finding.pageNumber(), finding.remediationSuggestion(), finding.source(),
                    finding.riskReason(), finding.createdTime(), finding.legalBasis(), finding.ruleExplanation());
        }
    }
}
