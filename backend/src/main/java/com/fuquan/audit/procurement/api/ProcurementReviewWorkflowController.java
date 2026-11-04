package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.procurement.application.ProcurementReviewWorkflowService;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/review-workflow")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementReviewWorkflowController {
    private final ProcurementReviewWorkflowService service;
    public ProcurementReviewWorkflowController(ProcurementReviewWorkflowService service) { this.service = service; }

    @PostMapping
    public ApiResponse<WorkflowResponse> start(@PathVariable UUID projectId) {
        return ApiResponse.success(WorkflowResponse.from(service.start(projectId)));
    }

    @GetMapping
    public ApiResponse<WorkflowResponse> get(@PathVariable UUID projectId) {
        return ApiResponse.success(WorkflowResponse.from(service.require(projectId)));
    }

    public record WorkflowResponse(UUID id, UUID projectId, int currentStepOrder, String status,
                                   Instant createdTime, Instant updatedTime, List<StepResponse> steps) {
        static WorkflowResponse from(ProcurementReviewWorkflowService.State state) {
            return new WorkflowResponse(state.workflow().id(), state.workflow().projectId(),
                    state.workflow().currentStepOrder(), state.workflow().status(), state.workflow().createdTime(),
                    state.workflow().updatedTime(), state.steps().stream().map(StepResponse::from).toList());
        }
    }
    public record StepResponse(String stepCode, int stepOrder, String stepName, String status,
                               Instant completedTime, String resultSummary) {
        static StepResponse from(ProcurementReviewStep value) {
            return new StepResponse(value.stepCode(), value.stepOrder(), value.stepName(), value.status(),
                    value.completedTime(), value.resultSummary());
        }
    }
}
