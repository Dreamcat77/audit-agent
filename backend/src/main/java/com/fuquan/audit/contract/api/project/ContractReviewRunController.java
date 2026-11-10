package com.fuquan.audit.contract.api.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateResult;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateException;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunDiscoveryService;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunService;
import com.fuquan.audit.contract.application.reviewrun.CreateContractReviewRunCommand;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutionService;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepStartTransactionCoordinator;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionMode;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionOwnership;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.security.application.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.HashSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractReviewRunController {
    private final ContractReviewRunService service;
    private final ReviewStepExecutionService executions;
    private final ReviewStepOutputReadService outputs;
    private final ContractReviewRunDiscoveryService discovery;
    private final ReviewExecutionOwnership executionOwnership;
    private final AuthContext auth;
    public ContractReviewRunController(ContractReviewRunService service,
            ReviewStepExecutionService executions, ReviewStepOutputReadService outputs,
            ContractReviewRunDiscoveryService discovery, ReviewExecutionOwnership executionOwnership,
            AuthContext auth) {
        this.service = service; this.executions = executions; this.outputs = outputs;
        this.discovery = discovery;
        this.executionOwnership = executionOwnership;
        this.auth = auth;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReviewRunResponse>> create(
            @PathVariable UUID projectId, @PathVariable UUID contractId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody JsonNode json, HttpServletRequest request) {
        CreateRequest body = CreateRequest.from(json);
        var result = service.create(new CreateContractReviewRunCommand(projectId, contractId,
                body.expectedContractVersion(), body.materialFileIds(), key, metadata(request)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                ReviewRunResponse.from(result, executionOwnership.mode())));
    }

    @ExceptionHandler(ContractReviewRunCreateException.class)
    public ResponseEntity<ApiResponse<CreateConflictResponse>> handleCreateConflict(
            ContractReviewRunCreateException exception) {
        HttpStatus status = exception.errorCode().httpStatus();
        return ResponseEntity.status(status).body(ApiResponse.failure(
                status.value(), exception.getMessage(),
                new CreateConflictResponse(exception.classification().name())));
    }

    @GetMapping("/latest")
    public ApiResponse<ReviewRunResponse> getLatest(
            @PathVariable UUID projectId, @PathVariable UUID contractId) {
        return ApiResponse.success(ReviewRunResponse.from(
                discovery.getLatest(projectId, contractId), executionOwnership.mode()));
    }

    @GetMapping("/{runId}")
    public ApiResponse<ReviewRunResponse> get(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId) {
        var output = outputs.getRun(projectId, contractId, runId);
        requireOwner(output.manifest().run().requestedBy());
        return ApiResponse.success(ReviewRunResponse.from(output, executionOwnership.mode()));
    }

    @GetMapping("/{runId}/steps/{stepId}")
    public ApiResponse<StepResponse> getStep(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID stepId) {
        var output = outputs.getStep(projectId, contractId, runId, stepId);
        requireOwner(output.run().requestedBy());
        return ApiResponse.success(StepResponse.from(output, false));
    }

    private void requireOwner(String requestedBy) {
        String actorId = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
        if (!requestedBy.equals(actorId)) throw new BusinessException(BusinessErrorCode.NOT_FOUND);
    }

    @PostMapping("/{runId}/steps/{stepId}/execute")
    public ApiResponse<StepResponse> executeStep(@PathVariable UUID projectId,
            @PathVariable UUID contractId, @PathVariable UUID runId, @PathVariable UUID stepId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (executionOwnership.workerOwnsExecution())
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "review step execution is owned by the server worker");
        if (body != null && (!body.isObject() || body.size() != 0))
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "review step execute body must be empty");
        var result = executions.execute(new ReviewStepStartTransactionCoordinator.StartCommand(
                projectId, contractId, runId, stepId, key, metadata(request)));
        return ApiResponse.success(StepResponse.from(result.output(), result.replayed()));
    }

    public record CreateRequest(Long expectedContractVersion, List<UUID> materialFileIds) {
        static CreateRequest from(JsonNode json) {
            if (json == null || !json.isObject() || json.size() != 2
                    || !json.has("expectedContractVersion") || !json.has("materialFileIds"))
                throw invalidRequest();
            JsonNode version = json.get("expectedContractVersion");
            JsonNode materials = json.get("materialFileIds");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0
                    || !materials.isArray() || materials.isEmpty()) throw invalidRequest();
            List<UUID> ids = new ArrayList<>();
            try {
                for (JsonNode file : materials) {
                    if (!file.isTextual()) throw invalidRequest();
                    ids.add(UUID.fromString(file.textValue()));
                }
            } catch (IllegalArgumentException invalid) { throw invalidRequest(); }
            if (new HashSet<>(ids).size() != ids.size()) throw invalidRequest();
            return new CreateRequest(version.longValue(), List.copyOf(ids));
        }

        private static BusinessException invalidRequest() {
            return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid review run create request");
        }
    }

    public record CreateConflictResponse(String classification) { }

    public record ReviewRunResponse(UUID runId, UUID projectId, UUID contractId, long contractVersion,
            ReviewExecutionMode executionMode,
            UUID sourceCandidateId, UUID sourceConfirmationId, int runSchemaVersion,
            String contractSnapshotCanonicalJson, String contractSnapshotSha256,
            String reviewProfileVersion, String ruleVersion, String modelPolicyVersion,
            String inputSnapshotIdentity, String requestedBy, ContractReviewRunStatus status,
            Instant requestedAt, Instant startedAt, Instant completedAt, Instant failedAt,
            Instant unknownAt, String safeReasonCode, long versionNo,
            List<ContractReviewRunMaterial> materials, List<ContractReviewStep> steps,
            List<ContractReviewStepAttempt> attempts,
            List<StepResponse> outputs, boolean replayed,
            com.fuquan.audit.contract.application.closeout.BusinessReviewProjection.Projection businessProjection) {
        static ReviewRunResponse from(ContractReviewRunCreateResult result, ReviewExecutionMode executionMode) {
            var run = result.run();
            return new ReviewRunResponse(run.runId(), run.projectId(), run.contractId(), run.contractVersion(),
                    executionMode,
                    run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(),
                    run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                    run.ruleVersion(), run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(),
                    run.status(), run.requestedAt(), run.startedAt(), run.completedAt(), run.failedAt(),
                    run.unknownAt(), run.safeReasonCode(), run.versionNo(), result.materials(),
                    result.steps(), result.attempts(), List.of(), result.replayed(), null);
        }
        static ReviewRunResponse from(ReviewStepOutputReadService.RunOutput output,
                ReviewExecutionMode executionMode) {
            var manifest = output.manifest();
            var run = manifest.run();
            return new ReviewRunResponse(run.runId(), run.projectId(), run.contractId(), run.contractVersion(),
                    executionMode,
                    run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(),
                    run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                    run.ruleVersion(), run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(),
                    run.status(), run.requestedAt(), run.startedAt(), run.completedAt(), run.failedAt(),
                    run.unknownAt(), run.safeReasonCode(), run.versionNo(), manifest.materials(),
                    manifest.steps(), manifest.attempts(), output.steps().stream()
                            .map(value -> StepResponse.from(value, false)).toList(), manifest.replayed(),
                    com.fuquan.audit.contract.application.closeout.BusinessReviewProjection.from(output));
        }
    }

    public record StepResponse(com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun run,
            ContractReviewStep step, List<ContractReviewStepAttempt> attempts,
            com.fuquan.audit.review.domain.AuditTask auditTask,
            List<com.fuquan.audit.review.domain.AuditCheckResult> auditCheckResults,
            List<FindingResponse> findings,
            List<com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence> evidence,
            java.util.Map<UUID, UUID> findingCheckResultRelations,
            boolean replayed) {
        static StepResponse from(ReviewStepOutputReadService.StepOutput output, boolean replayed) {
            java.util.Map<UUID, UUID> relations = new java.util.LinkedHashMap<>();
            if (com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE
                    .equals(output.step().outputResourceType())
                    && output.step().stepType() == com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType.F02_DETERMINISTIC) {
                for (var check : output.auditCheckResults()) {
                    UUID findingId = com.fuquan.audit.contract.application.reviewrun.executor
                            .FrozenF02ResultMapper.findingId(check.id());
                    if (output.findings().stream().anyMatch(value -> value.findingId().equals(findingId))) {
                        relations.put(findingId, check.id());
                    }
                }
            }
            java.util.Map<UUID, com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison> typed =
                    output.typedComparisons().stream().collect(java.util.stream.Collectors.toMap(
                            value -> value.findingId(), java.util.function.Function.identity()));
            List<FindingResponse> findingResponses = output.findings().stream()
                    .map(value -> FindingResponse.from(value, typed.get(value.findingId()))).toList();
            return new StepResponse(output.run(), output.step(), output.attempts(), output.auditTask(),
                    output.auditCheckResults(), findingResponses, output.evidence(),
                    java.util.Map.copyOf(relations), replayed);
        }
    }

    public record FindingResponse(UUID findingId, UUID runId, UUID stepId, UUID attemptId,
            int ordinal, String findingType, String category, String statusCode,
            String attentionCode, String riskLevel, String summary, String recommendation,
            int findingSchemaVersion, String policyVersion,
            com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource source,
            String modelProvider, String modelName, String modelRevision, String promptVersion,
            String modelSchemaVersion, Instant createdAt, long versionNo,
            com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison typedComparison) {
        static FindingResponse from(
                com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding value,
                com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison typed) {
            return new FindingResponse(value.findingId(), value.runId(), value.stepId(),
                    value.attemptId(), value.ordinal(), value.findingType(), value.category(),
                    value.statusCode(), value.attentionCode(), value.riskLevel(), value.summary(),
                    value.recommendation(), value.findingSchemaVersion(), value.policyVersion(),
                    value.source(), value.modelProvider(), value.modelName(), value.modelRevision(),
                    value.promptVersion(), value.modelSchemaVersion(), value.createdAt(),
                    value.versionNo(), typed);
        }
    }

    private static ProjectRequestMetadata metadata(HttpServletRequest request) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String traceId = MDC.get("traceId");
        return new ProjectRequestMetadata(requestId, traceId == null ? requestId : traceId,
                request.getRemoteAddr());
    }
}
