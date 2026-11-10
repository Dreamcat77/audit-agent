package com.fuquan.audit.budget.api;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisUseCase;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.web.RequestIdFilter;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budget/review-tasks")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class BudgetSemanticAnalysisController {

    private static final String OPERATION = "BUDGET_SEMANTIC_ANALYSIS";

    private final BudgetSemanticAnalysisUseCase useCase;
    private final FlowDebugLogger flowDebug;

    public BudgetSemanticAnalysisController(BudgetSemanticAnalysisUseCase useCase,
                                            FlowDebugLogger flowDebug) {
        this.useCase = useCase;
        this.flowDebug = flowDebug;
    }

    @PostMapping("/{taskId}/ai/semantic-analysis")
    public ApiResponse<BudgetSemanticAnalysisResponse> analyze(@PathVariable UUID taskId) {
        BudgetSemanticAnalysisResult result = useCase.analyze(taskId);
        flowDebug.logAiAnalysis("AI_SEMANTIC_ANALYSIS_COMPLETE", "BUDGET", OPERATION,
                taskId.toString(), result.available() ? "SUCCESS" : "UNAVAILABLE",
                result.model(), result.durationMs());
        return ApiResponse.success(new BudgetSemanticAnalysisResponse(
                taskId, OPERATION, requestId(), result.available(), result.items(),
                result.model(), result.durationMs()));
    }

    private static String requestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "unavailable" : requestId;
    }

    public record BudgetSemanticAnalysisResponse(
            UUID taskId,
            String operation,
            String requestId,
            boolean available,
            List<BudgetSemanticAnalysisResult.Item> items,
            String model,
            long durationMs) {
    }
}
