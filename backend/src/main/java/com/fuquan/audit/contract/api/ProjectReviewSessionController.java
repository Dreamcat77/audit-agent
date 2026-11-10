package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ReviewRequestMetadata;
import com.fuquan.audit.contract.application.session.ProjectReviewSessionService;
import com.fuquan.audit.contract.domain.ReviewSessionProjectFileBinding;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-sessions")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ProjectReviewSessionController {
    private final ProjectReviewSessionService service;
    private final Tracer tracer;
    public ProjectReviewSessionController(ProjectReviewSessionService service,Tracer tracer) { this.service=service; this.tracer=tracer; }
    @PostMapping
    public ApiResponse<ProjectReviewSessionService.View> create(@PathVariable UUID projectId,@PathVariable UUID contractId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body,HttpServletRequest request) {
        return ApiResponse.success(service.create(projectId,contractId,body.name(),body.remark(),body.fileIds(),key,metadata(request)));
    }
    @GetMapping
    public ApiResponse<List<ProjectReviewSessionService.View>> list(@PathVariable UUID projectId,@PathVariable UUID contractId) {
        return ApiResponse.success(service.list(projectId,contractId));
    }
    @GetMapping("/{sessionId}")
    public ApiResponse<ProjectReviewSessionService.View> get(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID sessionId) {
        return ApiResponse.success(service.get(projectId,contractId,sessionId));
    }
    @PostMapping("/{sessionId}/materials")
    public ApiResponse<ProjectReviewSessionService.View> replace(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Selection body,HttpServletRequest request) {
        return ApiResponse.success(service.replace(projectId,contractId,sessionId,body.expectedVersion(),body.fileIds(),key,metadata(request)));
    }
    @GetMapping("/{sessionId}/materials")
    public ApiResponse<List<ReviewSessionProjectFileBinding>> history(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID sessionId) {
        return ApiResponse.success(service.history(projectId,contractId,sessionId));
    }
    @PostMapping("/{sessionId}/confirm-materials")
    public ApiResponse<ProjectReviewSessionService.View> confirm(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Confirmation body,HttpServletRequest request) {
        return ApiResponse.success(service.confirm(projectId,contractId,sessionId,body.expectedVersion(),key,metadata(request)));
    }
    public record Create(@NotBlank @Size(max=256) String name,@Size(max=1000) String remark,
            @NotEmpty @Size(max=100) List<@NotNull UUID> fileIds) {}
    public record Selection(@Min(0) long expectedVersion,@NotEmpty @Size(max=100) List<@NotNull UUID> fileIds) {}
    public record Confirmation(@Min(0) long expectedVersion) {}
    private ReviewRequestMetadata metadata(HttpServletRequest request) {
        var span=tracer.currentSpan();
        if (span==null) throw new IllegalStateException("HTTP request has no tracing span");
        return new ReviewRequestMetadata(MDC.get(RequestIdFilter.MDC_KEY),span.context().traceId(),request.getRemoteAddr());
    }
}
