package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.procurement.application.ProcurementMaterialBatchService;
import com.fuquan.audit.security.application.AuthContext;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/review-materials")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementMaterialBatchController {
    private final ProcurementMaterialBatchService service;
    private final AuthContext auth;
    private final com.fuquan.audit.procurement.application.ProcurementIntakeReviewService intakeReview;
    public ProcurementMaterialBatchController(ProcurementMaterialBatchService service, AuthContext auth,
            com.fuquan.audit.procurement.application.ProcurementIntakeReviewService intakeReview) {
        this.service=service; this.auth=auth; this.intakeReview=intakeReview;
    }
    @PostMapping("/from-intake/analyze")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
    public ApiResponse<Response> analyzeIntake(@PathVariable UUID projectId) {
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        return ApiResponse.success(Response.from(intakeReview.analyze(projectId, user)));
    }
    @PostMapping(value="/batch", consumes="multipart/form-data")
    public ApiResponse<Response> upload(@PathVariable UUID projectId, @RequestPart("files") List<MultipartFile> files) {
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        boolean canQuote = user.roles().stream().anyMatch(Set.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR")::contains);
        var uploads = files.stream().map(f -> new ProcurementMaterialBatchService.Upload(f.getOriginalFilename(), f.getContentType(), f.getSize(), f::getInputStream)).toList();
        return ApiResponse.success(Response.from(service.upload(projectId, uploads, user.userId(), canQuote)));
    }
    @GetMapping("/current")
    public ApiResponse<Response> current(@PathVariable UUID projectId) { return ApiResponse.success(Response.from(service.reopen(projectId))); }
    @PostMapping(value="/uploads", consumes="multipart/form-data")
    public ApiResponse<Response> stage(@PathVariable UUID projectId, @RequestPart("files") List<MultipartFile> files) {
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        boolean canQuote = user.roles().stream().anyMatch(Set.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR")::contains);
        var uploads = files.stream().map(f -> new ProcurementMaterialBatchService.Upload(f.getOriginalFilename(), f.getContentType(), f.getSize(), f::getInputStream)).toList();
        return ApiResponse.success(Response.from(service.stage(projectId, uploads, user.userId(), canQuote)));
    }
    public record AnalysisRequest(List<UUID> sourceIds) {}
    @PostMapping("/analyze")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
    public ApiResponse<Response> analyze(@PathVariable UUID projectId, @RequestBody AnalysisRequest request) {
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        return ApiResponse.success(Response.from(service.analyzeSaved(projectId, request.sourceIds(), user.userId(), true)));
    }
    public record BidderRequest(String bidderName) {}
    @PostMapping("/{sourceId}/bidder")
    public ApiResponse<Response> confirm(@PathVariable UUID projectId, @PathVariable UUID sourceId, @RequestBody BidderRequest request) {
        var user = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        return ApiResponse.success(Response.from(service.confirmBidder(projectId, sourceId, request.bidderName(), user.userId())));
    }
    public record Response(List<ProcurementMaterialBatchService.FileResult> files,
            ProcurementQuoteCheckController.QuoteCheckResponse quote,
            ProcurementCollusionReviewController.ReviewResponse collusion,
            ProcurementReviewBlacklistController.BatchCheckResponse blacklist, List<String> notices,
            ProcurementReviewReportController.ReportResponse report) {
        public static Response from(ProcurementMaterialBatchService.Result r) { return new Response(r.files(),
                r.quote()==null?null:ProcurementQuoteCheckController.QuoteCheckResponse.from(r.quote()),
                r.collusion()==null?null:ProcurementCollusionReviewController.ReviewResponse.from(r.collusion()),
                r.blacklist()==null?null:ProcurementReviewBlacklistController.BatchCheckResponse.from(r.blacklist()),r.notices(),
                r.report()==null?null:ProcurementReviewReportController.ReportResponse.from(r.report())); }
    }
}
