package com.fuquan.audit.contract.api.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.closeout.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.closeout.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class ContractReviewCloseoutController {
    private final ContractReviewAcknowledgementService acknowledgements;
    private final ContractReviewReportService reports;
    public ContractReviewCloseoutController(ContractReviewAcknowledgementService acknowledgements, ContractReviewReportService reports) {
        this.acknowledgements = acknowledgements; this.reports = reports;
    }
    @PostMapping("/findings/{findingId}/acknowledgements")
    public ApiResponse<ContractReviewFindingAcknowledgement> acknowledge(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID findingId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        if (body == null || !body.isObject() || !body.has("findingVersionNo")
                || body.size() != (body.has("handlingNote") ? 2 : 1)) throw invalid();
        var version = body.get("findingVersionNo");
        var note = body.get("handlingNote");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0
                || (note != null && !note.isNull() && !note.isTextual())) throw invalid();
        return ApiResponse.success(acknowledgements.acknowledge(projectId, contractId, runId, findingId,
                version.longValue(), note == null || note.isNull() ? null : note.textValue(), key, metadata(request)));
    }
    @GetMapping("/findings/{findingId}/acknowledgements")
    public ApiResponse<List<ContractReviewFindingAcknowledgement>> acknowledgements(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID findingId) {
        return ApiResponse.success(acknowledgements.list(projectId, contractId, runId, findingId));
    }
    @GetMapping("/findings/{findingId}/acknowledgements/{acknowledgementId}")
    public ApiResponse<ContractReviewFindingAcknowledgement> acknowledgement(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID findingId,
            @PathVariable UUID acknowledgementId) {
        return ApiResponse.success(acknowledgements.get(projectId, contractId, runId, findingId, acknowledgementId));
    }
    @PostMapping("/reports")
    public ResponseEntity<ApiResponse<ReportResponse>> generate(@PathVariable UUID projectId, @PathVariable UUID contractId,
            @PathVariable UUID runId, @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (body != null && (!body.isObject() || !body.isEmpty())) throw invalid();
        return ResponseEntity.accepted().body(ApiResponse.success(ReportResponse.from(reports.generate(projectId, contractId, runId, key, metadata(request)))));
    }
    @GetMapping("/reports")
    public ApiResponse<List<ReportResponse>> reports(@PathVariable UUID projectId, @PathVariable UUID contractId, @PathVariable UUID runId) {
        return ApiResponse.success(reports.list(projectId, contractId, runId).stream().map(ReportResponse::from).toList());
    }
    @GetMapping("/reports/{reportId}")
    public ApiResponse<ReportResponse> report(@PathVariable UUID projectId, @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID reportId) {
        return ApiResponse.success(ReportResponse.from(reports.get(projectId, contractId, runId, reportId)));
    }
    @GetMapping("/reports/{reportId}/artifact")
    public ResponseEntity<byte[]> artifact(@PathVariable UUID projectId, @PathVariable UUID contractId,
            @PathVariable UUID runId, @PathVariable UUID reportId, HttpServletRequest request) {
        var artifact = reports.download(projectId, contractId, runId, reportId, metadata(request));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.mediaType()))
                .header("Content-Disposition", "attachment; filename=contract-review-report.html")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(artifact.bytes());
    }
    public record ReportResponse(UUID reportId, UUID reviewRunId, int reportVersion, int reportSchemaVersion,
            ContractReviewReport.Status status, String artifactMediaType, Long artifactSize, String generatedBy,
            Instant requestedAt, Instant generatedAt, Instant failedAt, Instant unknownAt) {
        static ReportResponse from(ContractReviewReport r) {
            return new ReportResponse(r.reportId(), r.reviewRunId(), r.reportVersion(), r.reportSchemaVersion(), r.status(),
                    r.artifactMediaType(), r.artifactSize(), r.generatedBy(), r.requestedAt(), r.generatedAt(), r.failedAt(), r.unknownAt());
        }
    }
    private static BusinessException invalid() { return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid closeout request"); }
    private static ProjectRequestMetadata metadata(HttpServletRequest request) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY), traceId = MDC.get("traceId");
        return new ProjectRequestMetadata(requestId, traceId == null ? requestId : traceId, request.getRemoteAddr());
    }
}
