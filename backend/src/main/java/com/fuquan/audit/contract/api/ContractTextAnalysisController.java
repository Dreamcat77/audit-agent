package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractTextAnalysisController {

    private static final String OPERATION = "CONTRACT_TEXT_ANALYSIS";

    private final ContractTextAnalysisUseCase useCase;
    private final FlowDebugLogger flowDebug;

    public ContractTextAnalysisController(ContractTextAnalysisUseCase useCase,
                                          FlowDebugLogger flowDebug) {
        this.useCase = useCase;
        this.flowDebug = flowDebug;
    }

    @PostMapping("/{projectId}/ai/contract-text-analysis")
    public ApiResponse<ContractTextAnalysisResponse> analyze(
            @PathVariable UUID projectId,
            @Valid @RequestBody ContractTextAnalysisRequestBody body) {
        ContractTextAnalysisResult result;
        try {
            result = useCase.analyze(projectId, body.fileIds());
        } catch (com.fuquan.audit.common.exception.ExternalServiceException failure) {
            if (failure.getCause() instanceof com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure classified
                    && classified.reason() == com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure.Reason.MODEL_INPUT_TOO_LARGE) {
                throw new BusinessException(BusinessErrorCode.PAYLOAD_TOO_LARGE,
                        "本批合同材料文本超过分析上限，请按合同或章节减少本次选择的材料；已上传文件保留，无需重新上传");
            }
            throw failure;
        }
        flowDebug.logAiAnalysis("AI_SEMANTIC_ANALYSIS_COMPLETE", "CONTRACT", OPERATION,
                projectId.toString(), result.available() ? "SUCCESS" : "UNAVAILABLE",
                result.model(), result.durationMs(), body.fileIds().size(), result.hints().size());
        return ApiResponse.success(new ContractTextAnalysisResponse(
                projectId, OPERATION, requestId(), result.available(), result.summaries(),
                result.hints(), result.model(), result.durationMs()));
    }

    public record ContractTextAnalysisRequestBody(
            @NotEmpty @Size(max = 50) List<@NotNull UUID> fileIds) {

        public ContractTextAnalysisRequestBody {
            if (fileIds != null) {
                Set<UUID> unique = new HashSet<>();
                for (UUID fileId : fileIds) {
                    if (fileId != null && !unique.add(fileId)) {
                        throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                                "fileIds must contain unique entries");
                    }
                }
                if (fileIds.stream().noneMatch(java.util.Objects::isNull)) {
                    fileIds = List.copyOf(fileIds);
                }
            }
        }
    }

    private static String requestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "unavailable" : requestId;
    }

    public record ContractTextAnalysisResponse(
            UUID projectId,
            String operation,
            String requestId,
            boolean available,
            List<String> summaries,
            List<ContractTextAnalysisResult.Hint> hints,
            String model,
            long durationMs) {
    }
}
