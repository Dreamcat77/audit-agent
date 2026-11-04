package com.fuquan.audit.contract.api.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.RetryReviewStepCommand;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepRetryService;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@Profile({"local-dev", "dev29", "customer"})
@ConditionalOnProperty(name = "audit.contract.review-worker.enabled", havingValue = "true")
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/retries")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class ContractReviewStepRetryController {
    private static final Set<String> FIELDS = Set.of("expectedRunVersion", "expectedStepVersion", "previousAttemptId",
            "previousAttemptNo", "previousAttemptVersion", "previousStatus", "previousExternalCallState", "reason");
    private final ReviewStepRetryService service;
    public ContractReviewStepRetryController(ReviewStepRetryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<ReviewStepRetryService.RetryResult>> retry(
            @PathVariable UUID projectId, @PathVariable UUID contractId, @PathVariable UUID runId,
            @PathVariable UUID stepId, @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        RetryReviewStepCommand command;
        try {
            if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw invalid();
            var names = body.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
            for (String field : Set.of("expectedRunVersion", "expectedStepVersion", "previousAttemptVersion", "previousAttemptNo"))
                if (!body.path(field).isIntegralNumber() || !body.path(field).canConvertToLong()) throw invalid();
            if (!body.path("previousAttemptNo").canConvertToInt()) throw invalid();
            for (String field : Set.of("previousAttemptId", "previousStatus", "previousExternalCallState", "reason"))
                if (!body.path(field).isTextual()) throw invalid();
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            command = new RetryReviewStepCommand(projectId, contractId, runId, stepId,
                    body.get("expectedRunVersion").longValue(), body.get("expectedStepVersion").longValue(),
                    UUID.fromString(body.get("previousAttemptId").textValue()), body.get("previousAttemptNo").intValue(),
                    body.get("previousAttemptVersion").longValue(), ContractReviewStepStatus.valueOf(body.get("previousStatus").textValue()),
                    ReviewStepExternalCallState.valueOf(body.get("previousExternalCallState").textValue()),
                    body.get("reason").textValue(), key, new ProjectRequestMetadata(requestId,
                    MDC.get("traceId") == null ? requestId : MDC.get("traceId"), request.getRemoteAddr()));
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.retry(command)));
    }
    private static BusinessException invalid() {
        return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid review retry request");
    }
}
