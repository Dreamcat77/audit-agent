package com.fuquan.audit.budget.api;

import com.fuquan.audit.budget.application.port.BudgetCheckPort;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import com.fuquan.audit.budget.application.BudgetReviewQueryService;
import com.fuquan.audit.budget.application.BudgetReviewService;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetCheckResult;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * F01 预算审批校验 — REST API。
 */
@RestController
@RequestMapping("/api/v1/budget")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class BudgetController {

    private final BudgetCheckPort budgetCheckPort;
    private final BudgetReviewService budgetReviewService;
    private final BudgetReviewQueryService budgetReviewQueryService;
    private final FlowDebugLogger flowDebugLogger;
    private final AuthContext authContext;
    private final com.fuquan.audit.budget.application.BudgetMaterialBatchService materialBatches;
    private com.fuquan.audit.security.application.port.UploadOwnershipPort uploadOwnership;
    @org.springframework.beans.factory.annotation.Autowired
    public void setUploadOwnership(com.fuquan.audit.security.application.port.UploadOwnershipPort value) { uploadOwnership=value; }
    private void requirePersonalOwnership(UUID taskId, CurrentUser user) {
        if(com.fuquan.audit.security.domain.PersonalUploadRoles.applies(user)
                && (uploadOwnership==null||!uploadOwnership.ownsBudgetTask(taskId,user.userId())))
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
    }

    public BudgetController(
            BudgetCheckPort budgetCheckPort,
            BudgetReviewService budgetReviewService,
            BudgetReviewQueryService budgetReviewQueryService,
            FlowDebugLogger flowDebugLogger,
            AuthContext authContext, com.fuquan.audit.budget.application.BudgetMaterialBatchService materialBatches) {
        this.budgetCheckPort = budgetCheckPort;
        this.budgetReviewService = budgetReviewService;
        this.budgetReviewQueryService = budgetReviewQueryService;
        this.flowDebugLogger = flowDebugLogger;
        this.authContext = authContext;
        this.materialBatches = materialBatches;
    }

    @PostMapping("/check")
    public ApiResponse<BudgetCheckResponse> check(@Valid @RequestBody BudgetCheckRequest request) {
        flowDebugLogger.log("F01_BUDGET_CHECK", "BUDGET", "WEB", "checkBudgetApproval");
        UUID procurementId = parseProcurementId(request.procurementId());
        List<BudgetCheckResult> results = budgetCheckPort.checkBudgetApproval(procurementId);
        return ApiResponse.success(BudgetCheckResponse.from(procurementId, results));
    }

    @PostMapping(value = "/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BudgetReviewTaskResponse> uploadMaterial(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String budgetYear,
            @RequestParam(required = false) String departmentCode) {
        try {
            BudgetReviewTask task = budgetReviewService.uploadAndParse(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream(),
                    budgetYear,
                    departmentCode);
            return ApiResponse.success(BudgetReviewTaskResponse.from(task));
        } catch (java.io.IOException exception) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "预算材料读取失败: " + exception.getMessage(),
                    exception);
        }
    }

    @PostMapping(value = "/upload-and-validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BudgetValidationBatchResponse> uploadAndValidate(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String budgetYear,
            @RequestParam(required = false) String departmentCode) {
        try {
            BudgetReviewTask task = budgetReviewService.uploadAndParse(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    file.getInputStream(), budgetYear, departmentCode);
            budgetReviewService.review(task.id());
            return ApiResponse.success(BudgetValidationBatchResponse.from(
                    budgetReviewQueryService.requireTask(task.id()),
                    budgetReviewQueryService.findItems(task.id())));
        } catch (java.io.IOException exception) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "预算材料读取失败: " + exception.getMessage(),
                    exception);
        }
    }

    @GetMapping("/validation-records")
    public ApiResponse<ValidationRecordPageResponse> listValidationRecords(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String budgetYear,
            @RequestParam(required = false) String departmentCode,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate createdFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate createdTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var filter = com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(fileName, createdFrom, createdTo);
        List<BudgetReviewTask> tasks = budgetReviewQueryService.findHistoryPage(
                batchId, budgetYear, departmentCode, filter, safePage, safeSize);
        List<ValidationRecordSummary> records = tasks.stream()
                .map(task -> ValidationRecordSummary.from(
                        task, budgetReviewQueryService.findItems(task.id())))
                .toList();
        return ApiResponse.success(new ValidationRecordPageResponse(
                records, safePage, safeSize,
                budgetReviewQueryService.countHistory(batchId, budgetYear, departmentCode, filter)));
    }

    @GetMapping("/validation-records/{batchId}")
    public ApiResponse<BudgetValidationBatchResponse> getValidationRecord(
            @PathVariable UUID batchId) {
        BudgetReviewTask task = budgetReviewQueryService.requireTask(batchId);
        return ApiResponse.success(BudgetValidationBatchResponse.from(
                task, budgetReviewQueryService.findItems(batchId)));
    }

    @PostMapping("/reviews/{taskId}")
    public ApiResponse<BudgetReviewResponse> review(@PathVariable UUID taskId) {
        budgetReviewService.review(taskId);
        return ApiResponse.success(BudgetReviewResponse.from(
                budgetReviewQueryService.requireTask(taskId),
                budgetReviewQueryService.findItems(taskId)));
    }

    @GetMapping("/reviews/{taskId}")
    public ApiResponse<BudgetReviewResponse> getReview(@PathVariable UUID taskId) {
        var response=BudgetReviewResponse.from(
                budgetReviewQueryService.requireTask(taskId),
                budgetReviewQueryService.findItems(taskId));
        return ApiResponse.success(response.withMaterialReference(materialBatches.result(taskId)));
    }

    @GetMapping("/reviews/{taskId}/exceptions")
    public ApiResponse<List<ReviewItemResponse>> getExceptions(@PathVariable UUID taskId) {
        BudgetReviewTask task = budgetReviewQueryService.requireTask(taskId);
        List<ReviewItemResponse> items = budgetReviewQueryService.findItems(taskId).stream()
                .filter(BudgetController::isException)
                .map(item -> ReviewItemResponse.from(task, item))
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/reviews/{taskId}/items/{itemId}")
    public ApiResponse<ReviewItemResponse> getItem(
            @PathVariable UUID taskId, @PathVariable UUID itemId) {
        BudgetReviewTask task = budgetReviewQueryService.requireTask(taskId);
        BudgetReviewItem item = budgetReviewQueryService.findItem(taskId, itemId);
        return ApiResponse.success(ReviewItemResponse.from(task, item));
    }

    @PostMapping("/validation/confirm-exception")
    public ApiResponse<ConfirmationProgressResponse> confirmException(
            @Valid @RequestBody ConfirmExceptionRequest request) {
        CurrentUser user = authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "当前登录用户不存在"));
        requirePersonalOwnership(parseBatchId(request.batchId()),user);
        BudgetReviewService.ConfirmationProgress progress = budgetReviewService.confirmException(
                parseBatchId(request.batchId()), request.rowNumber(), request.confirmStep(),
                request.abnormalReason(), request.remark(), user.userId(), user.displayName());
        return ApiResponse.success(ConfirmationProgressResponse.from(
                progress, budgetReviewQueryService.requireTask(parseBatchId(request.batchId()))));
    }

    @PostMapping("/validation/confirm-row")
    public ApiResponse<ConfirmationProgressResponse> confirmRow(
            @Valid @RequestBody ConfirmRowRequest request) {
        CurrentUser user = authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "当前登录用户不存在"));
        requirePersonalOwnership(parseBatchId(request.batchId()),user);
        BudgetReviewService.ConfirmationProgress progress = budgetReviewService.confirmRow(
                parseBatchId(request.batchId()), request.rowNumber(), request.remark(),
                user.userId(), user.displayName());
        return ApiResponse.success(ConfirmationProgressResponse.from(
                progress, budgetReviewQueryService.requireTask(parseBatchId(request.batchId()))));
    }

    @GetMapping("/validation/pending-confirm")
    public ApiResponse<PendingConfirmationResponse> pendingConfirmation(
            @RequestParam UUID batchId) {
        BudgetReviewTask task = budgetReviewQueryService.requireTask(batchId);
        boolean pending = "WAITING_CONFIRMATION".equals(task.status())
                && task.pendingRowNumber() != null;
        ReviewItemResponse item = pending
                ? budgetReviewQueryService.findItems(batchId).stream()
                        .filter(candidate -> task.pendingRowNumber().equals(candidate.rowNumber()))
                        .findFirst()
                        .map(candidate -> ReviewItemResponse.from(task, candidate))
                        .orElse(null)
                : null;
        return ApiResponse.success(PendingConfirmationResponse.from(task, item));
    }

    @GetMapping("/validation/confirm-logs")
    public ApiResponse<List<ConfirmLogResponse>> confirmLogs(@RequestParam UUID batchId) {
        return ApiResponse.success(budgetReviewQueryService.findConfirmLogs(batchId).stream()
                .map(ConfirmLogResponse::from)
                .toList());
    }

    private static boolean isException(BudgetReviewItem item) {
        return item.exceptionType() != null
                || !"MATCHED".equals(item.matchStatus())
                || !"PASSED".equals(item.amountStatus());
    }

    private static UUID parseProcurementId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT,
                    "procurementId must be a valid UUID",
                    exception);
        }
    }

    private static UUID parseBatchId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT, "batchId must be a valid UUID", exception);
        }
    }

    // ---- Request / Response records ----

    public record BudgetCheckRequest(@NotBlank(message = "procurementId is required") String procurementId) {}

    public record BudgetCheckResponse(
            String procurementId,
            boolean allPassed,
            int totalChecks,
            int passedChecks,
            List<CheckItemResponse> items) {

        static BudgetCheckResponse from(UUID procurementId, List<BudgetCheckResult> results) {
            long passed = results.stream().filter(BudgetCheckResult::passed).count();
            return new BudgetCheckResponse(
                    procurementId.toString(),
                    passed == results.size(),
                    results.size(),
                    (int) passed,
                    results.stream().map(CheckItemResponse::from).toList());
        }
    }

    public record CheckItemResponse(
            UUID id,
            String checkType,
            String subjectCode,
            String expectedValue,
            String actualValue,
            boolean passed,
            String errorDetail,
            Instant checkTime) {

        static CheckItemResponse from(BudgetCheckResult result) {
            return new CheckItemResponse(
                    result.id(), result.checkType(), result.subjectCode(),
                    result.expectedValue(), result.actualValue(),
                    result.passed(), result.errorDetail(), result.checkTime());
        }
    }

    public record BudgetReviewTaskResponse(
            UUID taskId,
            String fileName,
            String contentType,
            long fileSize,
            String status,
            int parsedLineCount,
            String errorDetail,
            String budgetYear,
            String departmentCode,
            int reviewCursor,
            Integer pendingRowNumber,
            String pendingStep,
            String pendingReason,
            Instant createdTime,
            Instant updatedTime) {

        public static BudgetReviewTaskResponse from(BudgetReviewTask task) {
            return new BudgetReviewTaskResponse(
                    task.id(), task.fileName(), task.contentType(), task.fileSize(),
                    task.status(), task.parsedLineCount(), java.util.Set.of("FAILED","UNKNOWN").contains(task.status())
                        ? com.fuquan.audit.budget.application.BudgetProcessingOutcome.description(task.status(),task.errorDetail()) : task.errorDetail(),
                    task.budgetYear(), task.departmentCode(),
                    task.reviewCursor(), task.pendingRowNumber(), task.pendingStep(), task.pendingReason(),
                    task.createdTime(), task.updatedTime());
        }
    }

    public record ConfirmExceptionRequest(
            @NotBlank String batchId,
            int rowNumber,
            @NotBlank String confirmStep,
            String abnormalReason,
            String remark) {
    }

    public record ConfirmRowRequest(
            @NotBlank String batchId,
            @Min(value = 1, message = "rowNumber must be positive") int rowNumber,
            String remark) {
    }

    public record ConfirmationProgressResponse(
            boolean confirmed,
            String nextStep,
            String executionStatus,
            boolean hasMoreException,
            Integer nextExceptionRow,
            ReviewItemResponse updatedRow,
            ReviewItemResponse nextRow) {
        static ConfirmationProgressResponse from(
                BudgetReviewService.ConfirmationProgress progress, BudgetReviewTask task) {
            ReviewItemResponse updatedRow = progress.updatedItem() == null
                    ? null
                    : ReviewItemResponse.from(task, progress.updatedItem());
            ReviewItemResponse nextRow = progress.nextItem() == null
                    ? null
                    : ReviewItemResponse.from(task, progress.nextItem());
            return new ConfirmationProgressResponse(
                    progress.confirmed(), progress.nextStep(), progress.executionStatus(),
                    progress.hasMoreException(), progress.nextExceptionRow(),
                    updatedRow, nextRow);
        }
    }

    public record PendingConfirmationResponse(
            boolean hasPending,
            List<PendingConfirmationItem> pendingList) {
        static PendingConfirmationResponse from(
                BudgetReviewTask task, ReviewItemResponse item) {
            if (item == null) {
                return new PendingConfirmationResponse(false, List.of());
            }
            return new PendingConfirmationResponse(true, List.of(
                    new PendingConfirmationItem(
                            item.rowNumber(), item.subjectName(),
                            task.pendingReason(), item.approvalAmount(), item.declaredAmount(),
                            item.differenceAmount())));
        }
    }

    public record PendingConfirmationItem(
            Integer rowNumber,
            String subjectName,
            String abnormalReason,
            java.math.BigDecimal expectAmount,
            java.math.BigDecimal actualAmount,
            java.math.BigDecimal differenceAmount) {
    }

    public record ConfirmLogResponse(
            long id,
            Instant confirmTime,
            String departmentName,
            String confirmerName,
            Integer rowNumber,
            String confirmStep,
            String abnormalReason,
            String remark) {
        static ConfirmLogResponse from(BudgetValidationConfirmLog log) {
            return new ConfirmLogResponse(
                    log.id(), log.confirmTime(), log.departmentName(), log.confirmerName(),
                    log.rowNumber(), log.confirmStep(), log.abnormalReason(), log.remark());
        }
    }

    public record BudgetReviewResponse(
            BudgetReviewTaskResponse task,
            boolean allPassed,
            int totalItems,
            int passedItems,
            int exceptionItems,
            List<ReviewItemResponse> items,
            com.fuquan.audit.budget.application.BudgetUploadedFacts.FileResult materialReference) {

        BudgetReviewResponse withMaterialReference(com.fuquan.audit.budget.application.BudgetUploadedFacts.FileResult value) {
            return new BudgetReviewResponse(task,value==null&&allPassed,totalItems,passedItems,exceptionItems,items,value);
        }

        static BudgetReviewResponse from(BudgetReviewTask task, List<BudgetReviewItem> items) {
        List<ReviewItemResponse> responses = items.stream()
                    .map(item -> ReviewItemResponse.from(task, item))
                    .toList();
            int passed = (int) responses.stream()
                    .filter(item -> "INFO".equals(item.riskLevel()))
                    .count();
            int exceptions = responses.size() - passed;
            return new BudgetReviewResponse(
                    BudgetReviewTaskResponse.from(task),
                    !responses.isEmpty() && passed == responses.size(),
                    responses.size(),
                    passed,
                    exceptions,
                    responses,null);
        }
    }

    public record ReviewItemResponse(
            UUID id,
            UUID taskId,
            Integer rowNumber,
            String subjectCode,
            String subjectName,
            java.math.BigDecimal declaredAmount,
            String supplierName,
            String projectCode,
            String approvalSubjectCode,
            String approvalSubjectName,
            java.math.BigDecimal approvalAmount,
            String approvalSupplierName,
            String matchStatus,
            String matchMethod,
            Integer matchDistance,
            String amountStatus,
            java.math.BigDecimal differenceAmount,
            String exceptionType,
            String errorDetail,
            Instant createdTime,
            String riskLevel,
            String missingFields,
            String amountFormatErrors,
            String codeFormatError,
            String requiredFieldsStatus,
            String amountFormatStatus,
            String categoryAttributionStatus,
            String categorySuggestion,
            String projectCodeStatus,
            String projectDepartmentStatus,
            String budgetYearValidityStatus,
            List<String> abnormalReasons,
            List<ReviewCheckResponse> checks,
            int currentStep,
            List<ValidationModuleGroup> moduleGroups,
            AiSemanticAnalysisResponse aiSemanticAnalysis) {

        static ReviewItemResponse from(BudgetReviewTask task, BudgetReviewItem item) {
            return new ReviewItemResponse(
                    item.id(), item.taskId(), item.rowNumber(), item.subjectCode(), item.subjectName(),
                    item.declaredAmount(), item.supplierName(), item.projectCode(), item.approvalSubjectCode(),
                    item.approvalSubjectName(), item.approvalAmount(), item.approvalSupplierName(),
                    item.matchStatus(), item.matchMethod(), item.matchDistance(),
                    item.amountStatus(), item.differenceAmount(), item.exceptionType(),
                    item.errorDetail(), item.createdTime(), item.riskLevel(), item.missingFields(),
                    item.amountFormatErrors(), item.codeFormatError(),
                    item.requiredFieldsStatus(), item.amountFormatStatus(),
                    item.categoryAttributionStatus(), item.categorySuggestion(),
                    item.projectCodeStatus(), item.projectDepartmentStatus(),
                    item.budgetYearValidityStatus(), item.abnormalReasons(),
                    checks(task, item), currentStep(task, item), moduleGroups(task, item),
                    aiSemanticAnalysis(item));
        }

        private static AiSemanticAnalysisResponse aiSemanticAnalysis(BudgetReviewItem item) {
            boolean available = !isBlank(item.aiSuggestedCategory())
                    || !isBlank(item.aiHint())
                    || item.aiConfidence() != null
                    || !isBlank(item.aiJudgment())
                    || item.aiSimilarityScore() != null
                    || !isBlank(item.aiEnhancedDescription())
                    || !isBlank(item.aiSuggestion());
            return new AiSemanticAnalysisResponse(
                    "BUDGET_SEMANTIC_ANALYSIS",
                    available,
                    available ? item.aiSuggestedCategory() : null,
                    available ? item.aiHint() : null,
                    available ? item.aiModel() : null,
                    available ? item.aiDurationMs() : null,
                    available ? item.aiConfidence() : null,
                    available ? item.aiJudgment() : null,
                    available ? item.aiSimilarityScore() : null,
                    available ? item.aiEnhancedDescription() : null,
                    available ? item.aiSuggestion() : null);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static int currentStep(BudgetReviewTask task, BudgetReviewItem item) {
            if (task != null && "WAITING_CONFIRMATION".equals(task.status())
                    && task.pendingRowNumber() != null
                    && task.pendingRowNumber().equals(item.rowNumber())) {
                return Math.min(10, checkIndex(task.pendingStep()) + 1);
            }
            if (isUnprocessedRow(task, item)) {
                return 0;
            }
            List<ReviewCheckResponse> checks = checks(task, item);
            for (int index = 0; index < checks.size(); index++) {
                String status = checks.get(index).status();
                if ("PENDING".equals(status) || "NOT_CHECKED".equals(status)) {
                    return index + 1;
                }
            }
            return checks.size();
        }

        private static List<ReviewCheckResponse> checks(
                BudgetReviewTask task, BudgetReviewItem item) {
            boolean pendingRow = task != null
                    && "WAITING_CONFIRMATION".equals(task.status())
                    && task.pendingRowNumber() != null
                    && task.pendingRowNumber().equals(item.rowNumber());
            int pendingIndex = pendingRow ? checkIndex(task.pendingStep()) : -1;
            boolean unprocessedRow = isUnprocessedRow(task, item);
            boolean completedTask = task != null && "REVIEWED".equals(task.status());
            return List.of(
                    required(item, statusFor("REQUIRED_FIELDS", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.requiredFieldsStatus()), item.missingFields()),
                    amountFormat(item, statusFor("AMOUNT_FORMAT", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.amountFormatStatus())),
                    subjectCode(item, statusFor("SUBJECT_CODE", item, pendingIndex, unprocessedRow,
                            completedTask, null)),
                    subjectName(item, statusFor("SUBJECT_NAME", item, pendingIndex, unprocessedRow,
                            completedTask, null)),
                    categoryAttribution(item, statusFor("CATEGORY_ATTRIBUTION", item, pendingIndex,
                            unprocessedRow, completedTask,
                            item.categoryAttributionStatus())),
                    budgetAmount(item, statusFor("BUDGET_COMPARE", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.amountStatus())),
                    supplier(item, statusFor("SUPPLIER_NAME", item, pendingIndex, unprocessedRow,
                            completedTask, null)),
                    projectCode(item, statusFor("PROJECT_CODE", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.projectCodeStatus())),
                    projectDepartment(item, statusFor("PROJECT_DEPARTMENT", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.projectDepartmentStatus())),
                    yearValidity(item, statusFor("BUDGET_YEAR_VALIDITY", item, pendingIndex, unprocessedRow,
                            completedTask,
                            item.budgetYearValidityStatus())));
        }

        private static List<ValidationModuleGroup> moduleGroups(
                BudgetReviewTask task, BudgetReviewItem item) {
            List<ReviewCheckResponse> checks = checks(task, item);
            return List.of(
                    module("DATA_VALIDATION", "数据规范性校验", checks.subList(0, 2)),
                    module("SUBJECT_CONSISTENCY", "科目一致性校验", checks.subList(2, 5)),
                    module("AMOUNT_COMPLIANCE", "金额合规性校验", checks.subList(5, 6)),
                    module("SUPPLIER_CHECK", "关联方校验", checks.subList(6, 7)),
                    module("PROJECT_COMPLIANCE", "项目合规性校验", checks.subList(7, 10)));
        }

        private static ValidationModuleGroup module(
                String key, String name, List<ReviewCheckResponse> children) {
            String status = children.stream().anyMatch(child ->
                    "FAILED".equals(child.status()) || "WARNING".equals(child.status()))
                    ? "ERROR"
                    : children.stream().anyMatch(child ->
                            "PENDING".equals(child.status()) || "NOT_CHECKED".equals(child.status()))
                            ? "PENDING" : "PASS";
            String summary = children.stream()
                    .filter(child -> "FAILED".equals(child.status()) || "WARNING".equals(child.status()))
                    .map(ReviewCheckResponse::errorDetail)
                    .filter(detail -> detail != null && !detail.isBlank())
                    .collect(java.util.stream.Collectors.joining("；"));
            return new ValidationModuleGroup(key, name, status, children, summary);
        }

        private static ReviewCheckResponse required(
                BudgetReviewItem item, String status, String missingFields) {
            return new ReviewCheckResponse(
                    "REQUIRED_FIELDS", "科目编码、科目名称、申报金额、供应商名称、项目编号",
                    filledFieldCount(item) + "/5"
                            + (missingFields == null ? "" : "（缺失：" + missingFields + "）"),
                    "PASSED".equals(status), status,
                    "PASSED".equals(status) ? "必填信息完整" : "必填信息缺失：" + missingFields);
        }

        private static ReviewCheckResponse amountFormat(BudgetReviewItem item, String status) {
            return new ReviewCheckResponse(
                    "AMOUNT_FORMAT", "正数，≤2位小数",
                    item.declaredAmount() == null ? item.amountFormatErrors() : item.declaredAmount().toPlainString(),
                    "PASSED".equals(status), status,
                    "PASSED".equals(status) ? "金额格式合法" : "金额格式非法：" + item.amountFormatErrors());
        }

        private static ReviewCheckResponse subjectCode(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            boolean unmatched = "UNMATCHED".equals(item.matchStatus())
                    || "DATA_ERROR".equals(item.matchStatus());
            return new ReviewCheckResponse(
                    "SUBJECT_CODE", unmatched ? null : item.approvalSubjectCode(), item.subjectCode(),
                    passed, status, passed ? "科目编码一致" :
                            unmatched ? "匹配失败" :
                                    item.codeFormatError() == null ? "科目编码为空或不一致" : item.codeFormatError());
        }

        private static ReviewCheckResponse budgetAmount(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            return new ReviewCheckResponse(
                    "BUDGET_AMOUNT", amountText(item.approvalAmount()), amountText(item.declaredAmount()),
                    passed, status, passed ? "申报金额不超过预算批复金额"
                            : "SKIPPED".equals(status)
                            ? "已跳过：科目未匹配到预算批复，暂无可比较金额"
                            : "OVER_BUDGET".equals(item.amountStatus())
                            ? "申报金额超过预算批复金额（" + amountText(item.differenceAmount()) + "）"
                            : item.errorDetail() == null ? "预算金额未校验" : item.errorDetail());
        }

        private static ReviewCheckResponse supplier(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            return new ReviewCheckResponse(
                    "SUPPLIER_NAME", item.approvalSupplierName(), item.supplierName(),
                    passed, status, passed ? "供应商名称一致" : "供应商名称为空或不一致");
        }

        private static ReviewCheckResponse subjectName(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            boolean unmatched = "UNMATCHED".equals(item.matchStatus())
                    || "DATA_ERROR".equals(item.matchStatus());
            return new ReviewCheckResponse(
                    "SUBJECT_NAME", unmatched ? null : item.approvalSubjectName(), item.subjectName(),
                    passed, status, passed ? "科目名称匹配成功"
                            : unmatched ? "匹配失败"
                                    : item.matchStatus() == null ? "科目名称无法匹配" : item.matchStatus());
        }

        private static ReviewCheckResponse categoryAttribution(
                BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            String detail = passed
                    ? (item.categoryAttributionStatus() == null
                    || "NOT_CHECKED".equals(item.categoryAttributionStatus())
                    ? "分类规则未配置，跳过校验" : "科目分类归属一致")
                    : item.abnormalReasons().stream()
                    .filter(reason -> reason.startsWith("科目分类归属"))
                    .findFirst()
                    .orElse("科目分类归属不匹配");
            return new ReviewCheckResponse(
                    "CATEGORY_ATTRIBUTION", "科目编码所属分类", item.subjectName(),
                    passed, status, detail);
        }

        private static ReviewCheckResponse projectCode(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            return new ReviewCheckResponse(
                    "PROJECT_CODE", "同批次唯一", item.projectCode(),
                    passed, status, passed ? "项目编号唯一" : "项目编号重复");
        }

        private static ReviewCheckResponse projectDepartment(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            return new ReviewCheckResponse(
                    "PROJECT_DEPARTMENT", "项目属于当前部门", item.projectCode(),
                    passed, status, passed ? "项目与部门匹配" : "项目不存在或不属于当前部门");
        }

        private static ReviewCheckResponse yearValidity(BudgetReviewItem item, String status) {
            boolean passed = "PASSED".equals(status);
            return new ReviewCheckResponse(
                    "BUDGET_YEAR_VALIDITY", "所选年度在有效期内", item.subjectCode(),
                    passed, status, passed ? "预算年度有效" : "预算年度无效");
        }

        private static String statusFor(
                String type,
                BudgetReviewItem item,
                int pendingIndex,
                boolean unprocessedRow,
                boolean completedTask,
                String storedStatus) {
            int index = checkIndex(type);
            if (unprocessedRow) {
                return "PENDING";
            }
            if (pendingIndex >= 0 && index > pendingIndex) {
                return "PENDING";
            }
            if (pendingIndex >= 0 && index == pendingIndex) {
                return "FAILED";
            }
            if (completedTask && "NOT_CHECKED".equals(storedStatus)) {
                return "PASSED";
            }
            // NOT_CHECKED is an explicit persisted state. Do not infer PASS from
            // populated input fields when the engine has not executed this step.
            if (storedStatus != null) {
                if ("BUDGET_COMPARE".equals(type)
                        && "NOT_CHECKED".equals(storedStatus)
                        && pendingIndex > index) {
                    return legacyBudgetComparisonStatus(item);
                }
                return storedStatus;
            }
            if ("REQUIRED_FIELDS".equals(type)) {
                return item.missingFields() == null ? "PASSED" : "FAILED";
            }
            if ("AMOUNT_FORMAT".equals(type)) {
                return item.amountFormatErrors() == null ? "PASSED" : "FAILED";
            }
            if ("SUBJECT_CODE".equals(type)) {
                return item.codeFormatError() == null
                        && item.subjectCode() != null ? "PASSED" : "FAILED";
            }
            if ("BUDGET_COMPARE".equals(type)) {
                return item.amountStatus() == null || "NOT_CHECKED".equals(item.amountStatus())
                        ? "PENDING" : "PASSED".equals(item.amountStatus()) ? "PASSED" : "FAILED";
            }
            if ("SUPPLIER_NAME".equals(type)) {
                return item.approvalSupplierName() == null
                        || equalText(item.approvalSupplierName(), item.supplierName()) ? "PASSED" : "FAILED";
            }
            if ("SUBJECT_NAME".equals(type)) {
                return "MATCHED".equals(item.matchStatus()) ? "PASSED" : "FAILED";
            }
            if ("PROJECT_CODE".equals(type)) {
                if (item.projectCodeStatus() != null
                        && !"NOT_CHECKED".equals(item.projectCodeStatus())) {
                    return item.projectCodeStatus();
                }
                return item.projectCode() == null || item.projectCode().isBlank()
                        ? "FAILED" : "PASSED";
            }
            if ("PROJECT_DEPARTMENT".equals(type)) {
                if (item.projectDepartmentStatus() != null
                        && !"NOT_CHECKED".equals(item.projectDepartmentStatus())) {
                    return item.projectDepartmentStatus();
                }
                return item.projectCode() == null || item.projectCode().isBlank()
                        ? "NOT_CHECKED" : "PASSED";
            }
            if ("BUDGET_YEAR_VALIDITY".equals(type)) {
                if (item.budgetYearValidityStatus() != null
                        && !"NOT_CHECKED".equals(item.budgetYearValidityStatus())) {
                    return item.budgetYearValidityStatus();
                }
                return item.subjectCode() == null || item.subjectCode().isBlank()
                        ? "NOT_CHECKED" : "PASSED";
            }
            return "NOT_CHECKED";
        }

        /**
         * Older tasks may have persisted NOT_CHECKED for the budget comparison
         * even though the engine continued past that step. Reconcile that
         * stale value from the persisted row facts so a later pause cannot
         * expose an earlier check as still pending.
         */
        private static String legacyBudgetComparisonStatus(BudgetReviewItem item) {
            boolean budgetDataMissing = item.abnormalReasons().stream()
                    .anyMatch(reason -> reason != null
                            && reason.startsWith("预算批复数据不存在"));
            if (budgetDataMissing) {
                return "FAILED";
            }
            if (item.approvalAmount() == null
                    && ("UNMATCHED".equals(item.matchStatus())
                    || item.approvalSubjectCode() == null
                    || item.approvalSubjectName() == null)) {
                return "SKIPPED";
            }
            if (item.approvalAmount() != null && item.declaredAmount() != null) {
                return item.declaredAmount().compareTo(item.approvalAmount()) <= 0
                        ? "PASSED" : "FAILED";
            }
            return "SKIPPED";
        }

        private static boolean isUnprocessedRow(BudgetReviewTask task, BudgetReviewItem item) {
            return task != null
                    && "WAITING_CONFIRMATION".equals(task.status())
                    && task.pendingRowNumber() != null
                    && item.rowNumber() != null
                    && item.rowNumber() > task.pendingRowNumber();
        }

        private static int checkIndex(String type) {
            return switch (type) {
                case "REQUIRED_FIELDS" -> 0;
                case "AMOUNT_FORMAT" -> 1;
                case "SUBJECT_CODE" -> 2;
                case "SUBJECT_NAME" -> 3;
                case "CATEGORY_ATTRIBUTION" -> 4;
                case "BUDGET_COMPARE" -> 5;
                case "SUPPLIER_NAME" -> 6;
                case "PROJECT_CODE" -> 7;
                case "PROJECT_DEPARTMENT" -> 8;
                case "BUDGET_YEAR_VALIDITY" -> 9;
                // 兼容旧版本把模块名写入 pendingStep 的历史任务。
                case "DATA_VALIDATION" -> 0;
                case "SUBJECT_CONSISTENCY" -> 2;
                case "AMOUNT_COMPLIANCE" -> 5;
                case "BUDGET_AMOUNT" -> 5;
                case "SUPPLIER_CHECK" -> 6;
                case "PROJECT_COMPLIANCE" -> 7;
                default -> -1;
            };
        }

        private static boolean equalText(String expected, String actual) {
            String left = com.fuquan.audit.budget.application.BudgetTextNormalizer.subject(expected);
            String right = com.fuquan.audit.budget.application.BudgetTextNormalizer.subject(actual);
            return left != null && right != null && left.equals(right);
        }

        private static String amountText(java.math.BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }

        private static int filledFieldCount(BudgetReviewItem item) {
            int count = 0;
            if (item.subjectCode() != null && !item.subjectCode().isBlank()) count++;
            if (item.subjectName() != null && !item.subjectName().isBlank()) count++;
            if (item.declaredAmount() != null) count++;
            if (item.supplierName() != null && !item.supplierName().isBlank()) count++;
            if (item.projectCode() != null && !item.projectCode().isBlank()) count++;
            return count;
        }
    }

    public record ReviewCheckResponse(
            String checkType,
            String expectedValue,
            String actualValue,
            boolean passed,
            String status,
            String errorDetail) {
    }

    public record ValidationModuleGroup(
            String moduleKey,
            String moduleName,
            String moduleStatus,
            List<ReviewCheckResponse> children,
            String exceptionSummary) {
    }

    public record AiSemanticAnalysisResponse(
            String operation,
            boolean available,
            String suggestedCategory,
            String hint,
            String model,
            Long durationMs,
            Double confidence,
            String judgment,
            Double similarityScore,
            String enhancedDescription,
            String suggestion) {
    }

    public record BudgetValidationBatchResponse(
            String batchId,
            int totalRows,
            int validRows,
            int criticalCount,
            int warningCount,
            int infoCount,
            List<ReviewItemResponse> details,
            ValidationSummary summary) {

        static BudgetValidationBatchResponse from(
                BudgetReviewTask task, List<BudgetReviewItem> items) {
            List<ReviewItemResponse> details = items.stream()
                    .map(item -> ReviewItemResponse.from(task, item))
                    .toList();
            BigDecimalTotals totals = BigDecimalTotals.from(items);
            return new BudgetValidationBatchResponse(
                    task.id().toString(),
                    details.size(),
                    (int) details.stream().filter(item -> "INFO".equals(item.riskLevel())).count(),
                    (int) details.stream().filter(item -> "CRITICAL".equals(item.riskLevel())).count(),
                    (int) details.stream().filter(item -> "WARNING".equals(item.riskLevel())).count(),
                    (int) details.stream().filter(item -> "INFO".equals(item.riskLevel())).count(),
                    details,
                    new ValidationSummary(
                            totals.totalDeclaredAmount(),
                            totals.totalApprovalAmount(),
                            (int) details.stream().filter(item -> "OVER_BUDGET".equals(item.amountStatus())).count(),
                            (int) details.stream().filter(item -> "UNMATCHED".equals(item.matchStatus())).count()));
        }
    }

    public record ValidationSummary(
            java.math.BigDecimal totalDeclareAmount,
            java.math.BigDecimal totalBudgetAmount,
            int overBudgetCount,
            int matchFailedCount) {
    }

    public record ValidationRecordPageResponse(
            List<ValidationRecordSummary> records,
            int page,
            int size,
            long total) {
    }

    public record ValidationRecordSummary(
            String batchId,
            String fileName,
            String status,
            String budgetYear,
            String departmentCode,
            java.time.Instant createdTime,
            int totalRows,
            int validRows,
            int criticalCount,
            int warningCount,
            int infoCount,
            String processingExplanation) {

        static ValidationRecordSummary from(BudgetReviewTask task, List<BudgetReviewItem> items) {
            return new ValidationRecordSummary(
                    task.id().toString(), task.fileName(), task.status(),
                    task.budgetYear(), task.departmentCode(), task.createdTime(), items.size(),
                    (int) items.stream().filter(item -> "INFO".equals(item.riskLevel())).count(),
                    (int) items.stream().filter(item -> "CRITICAL".equals(item.riskLevel())).count(),
                    (int) items.stream().filter(item -> "WARNING".equals(item.riskLevel())).count(),
                    (int) items.stream().filter(item -> "INFO".equals(item.riskLevel())).count(),
                    com.fuquan.audit.budget.application.BudgetProcessingOutcome.description(task.status(),task.errorDetail()));
        }
    }

    private record BigDecimalTotals(
            java.math.BigDecimal totalDeclaredAmount,
            java.math.BigDecimal totalApprovalAmount) {

        static BigDecimalTotals from(List<BudgetReviewItem> items) {
            return new BigDecimalTotals(
                    items.stream()
                            .map(BudgetReviewItem::declaredAmount)
                            .filter(java.util.Objects::nonNull)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                    items.stream()
                            .map(BudgetReviewItem::approvalAmount)
                            .filter(java.util.Objects::nonNull)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        }
    }
}
