package com.fuquan.audit.review.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.review.application.AcknowledgeFindingCommand;
import com.fuquan.audit.review.application.FindingAcknowledgementService;
import com.fuquan.audit.review.application.FindingAcknowledgementView;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes generic, immutable review finding acknowledgement facts. */
@RestController
@Validated
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class FindingAcknowledgementController {

    private final FindingAcknowledgementService acknowledgementService;
    private final AuthContext authContext;
    private final Tracer tracer;

    public FindingAcknowledgementController(
            FindingAcknowledgementService acknowledgementService,
            AuthContext authContext,
            Tracer tracer) {
        this.acknowledgementService = acknowledgementService;
        this.authContext = authContext;
        this.tracer = tracer;
    }

    /** Creates one irreversible acknowledgement; the command has no business request body. */
    @PostMapping("/audit-check-results/{checkResultId}/acknowledgement")
    public ApiResponse<FindingAcknowledgementResponse> acknowledge(
            @PathVariable UUID checkResultId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestBody(required = false)
            @Size(max = 0, message = "request body must be empty") byte[] requestBody) {
        Span span = tracer.currentSpan();
        if (span == null) {
            throw new IllegalStateException("HTTP acknowledgement request has no tracing span");
        }
        FindingAcknowledgementView acknowledgement = acknowledgementService.acknowledge(
                new AcknowledgeFindingCommand(
                        checkResultId,
                        idempotencyKey,
                        MDC.get(RequestIdFilter.MDC_KEY),
                        span.context().traceId()),
                requireCurrentUser());
        return ApiResponse.success(FindingAcknowledgementResponse.from(acknowledgement));
    }

    /** Reads the generic acknowledgement projection for one real audit task. */
    @GetMapping("/audit-tasks/{taskId}/finding-acknowledgements")
    public ApiResponse<List<FindingAcknowledgementResponse>> findByTaskId(
            @PathVariable UUID taskId) {
        return ApiResponse.success(acknowledgementService.findByTaskId(taskId).stream()
                .map(FindingAcknowledgementResponse::from)
                .toList());
    }

    private CurrentUser requireCurrentUser() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    /** Safe API projection of the immutable fact plus its current display name. */
    public record FindingAcknowledgementResponse(
            UUID id,
            UUID checkResultId,
            String acknowledgedBy,
            String acknowledgedByDisplayName,
            Instant acknowledgedAt,
            String requestId,
            String traceId,
            long ruleVersion) {

        static FindingAcknowledgementResponse from(FindingAcknowledgementView view) {
            return new FindingAcknowledgementResponse(
                    view.id(),
                    view.checkResultId(),
                    view.acknowledgedBy(),
                    view.acknowledgedByDisplayName(),
                    view.acknowledgedAt(),
                    view.requestId(),
                    view.traceId(),
                    view.ruleVersion());
        }
    }
}
