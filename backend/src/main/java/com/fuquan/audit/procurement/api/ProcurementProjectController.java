package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.procurement.application.ProcurementAgentReviewSnapshotService;
import com.fuquan.audit.procurement.application.ProcurementAgentReviewService;
import com.fuquan.audit.procurement.application.ProcurementProjectCommandService;
import com.fuquan.audit.procurement.application.ProcurementProjectExportService;
import com.fuquan.audit.procurement.application.ProcurementProjectQueryService;
import com.fuquan.audit.procurement.application.ProcurementSourceProjectService;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 采购项目查询和规则预审 REST API。 */
@RestController
@RequestMapping("/api/v1/procurement/projects")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementProjectController {

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementProjectCommandService projectCommandService;
    private final ProcurementProjectExportService projectExportService;
    private final ProcurementAgentReviewSnapshotService agentReviewSnapshotService;
    private final ProcurementSourceProjectService sourceProjectService;
    private final FlowDebugLogger flowDebugLogger;
    private final OperationAuditService operationAudit;
    private final AuthContext authContext;

    public ProcurementProjectController(ProcurementProjectQueryService projectQueryService,
                                        ProcurementProjectCommandService projectCommandService,
                                        ProcurementProjectExportService projectExportService,
                                        ProcurementAgentReviewSnapshotService agentReviewSnapshotService,
                                        ProcurementSourceProjectService sourceProjectService,
                                        FlowDebugLogger flowDebugLogger, OperationAuditService operationAudit,
                                        AuthContext authContext) {
        this.projectQueryService = projectQueryService;
        this.projectCommandService = projectCommandService;
        this.projectExportService = projectExportService;
        this.agentReviewSnapshotService = agentReviewSnapshotService;
        this.sourceProjectService = sourceProjectService;
        this.flowDebugLogger = flowDebugLogger;
        this.operationAudit = operationAudit;
        this.authContext = authContext;
    }

    @org.springframework.web.bind.annotation.PostMapping
    public ApiResponse<CreateProjectResponse> create(@Valid @org.springframework.web.bind.annotation.RequestBody CreateProjectRequest request) {
        CurrentUser user = currentUser();
        try {
            String projectCode = "PR-" + java.time.Year.now() + "-"
                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            var source = sourceProjectService.requireEligible(request.sourceProjectId());
            ProcurementProjectCommandPort.CreateCommand command = new ProcurementProjectCommandPort.CreateCommand(
                    source.id(), request.materialIds(), projectCode, request.projectName(), request.departmentName(), request.procurementType(),
                    request.budgetAmount(), request.subjectCode(), request.subjectName(), request.procurementMethod(),
                    request.requirementDescription(), request.applicantName(), request.attachmentName());
            UUID id = request.submitForApproval()
                    ? projectCommandService.create(command, user)
                    : projectCommandService.create(command);
            ProcurementProject created = projectQueryService.requireById(id);
            operationAudit.success("CREATE_PROJECT", user, "PROJECT", id.toString(), created.projectName(),
                    null, created, request.submitForApproval() ? "创建并提交采购项目" : "保存采购项目草稿");
            flowDebugLogger.log("PROCUREMENT_PROJECT_CREATE", "PROCUREMENT", "APPLICATION", "create");
            return ApiResponse.success(new CreateProjectResponse(id));
        } catch (RuntimeException exception) {
            operationAudit.failure("CREATE_PROJECT", user, "PROJECT", null,
                    request.sourceProjectId() == null ? null : request.sourceProjectId().toString(),
                    failureReason(exception));
            throw exception;
        }
    }

    @GetMapping("/source-projects")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
    public ApiResponse<List<SourceProjectResponse>> sourceProjects(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(sourceProjectService.findEligible(keyword, limit).stream()
                .map(SourceProjectResponse::from).toList());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR','FINANCE_REVIEWER')")
    public ApiResponse<PageResponse<ProjectResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        var result = projectQueryService.findPage(status, keyword, page, size);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var reviewSummaries = agentReviewSnapshotService.findSummaries(
                result.items().stream().map(ProcurementProject::id).toList());
        flowDebugLogger.log("PROCUREMENT_PROJECT_LIST", "PROCUREMENT", "WEB", "list");
        return ApiResponse.success(new PageResponse<>(
                result.items().stream()
                        .map(project -> ProjectResponse.from(project, reviewSummaries.get(project.id())))
                        .toList(), safePage, safeSize,
                result.total(), result.total() == 0 ? 0 : (result.total() + safeSize - 1) / safeSize));
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT')")
    public ApiResponse<ProjectResponse> update(
            @PathVariable String projectId, @Valid @org.springframework.web.bind.annotation.RequestBody UpdateProjectRequest request) {
        CurrentUser user = currentUser();
        try {
            UUID id = parseProjectId(projectId);
            ProcurementProject before = projectQueryService.requireById(id);
            ProcurementProject project = projectCommandService.update(id,
                    new ProcurementProjectCommandPort.UpdateCommand(
                            request.projectName(), request.departmentName(), request.procurementType(),
                            request.budgetAmount(), request.subjectCode(), request.subjectName(),
                            request.procurementMethod(), request.requirementDescription(), request.applicantName(),
                            request.attachmentName()));
            operationAudit.success("UPDATE_PROJECT", user, "PROJECT", id.toString(), project.projectName(),
                    before, project, "编辑采购项目");
            flowDebugLogger.log("PROCUREMENT_PROJECT_UPDATE", "PROCUREMENT", "APPLICATION", "update");
            return ApiResponse.success(ProjectResponse.from(project));
        } catch (RuntimeException exception) {
            operationAudit.failure("UPDATE_PROJECT", user, "PROJECT", projectId, request.projectName(), failureReason(exception));
            throw exception;
        }
    }

    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable String projectId) {
        CurrentUser user = currentUser();
        try {
            UUID id = parseProjectId(projectId);
            ProcurementProject before = projectQueryService.requireById(id);
            if (!"DRAFT".equalsIgnoreCase(before.status())) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "只有草稿状态的采购项目可以删除，当前状态: " + before.status());
            }
            projectCommandService.delete(id);
            operationAudit.success("DELETE_PROJECT", user, "PROJECT", id.toString(), before.projectName(),
                    before, null, "删除采购项目");
            flowDebugLogger.log("PROCUREMENT_PROJECT_DELETE", "PROCUREMENT", "APPLICATION", "delete");
            return ApiResponse.success(null);
        } catch (RuntimeException exception) {
            operationAudit.failure("DELETE_PROJECT", user, "PROJECT", projectId, null, failureReason(exception));
            throw exception;
        }
    }

    @GetMapping("/{projectId}/agent-review")
    public ApiResponse<AgentReviewResponse> agentReview(@PathVariable String projectId) {
        UUID id = parseProjectId(projectId);
        return ApiResponse.success(agentReviewSnapshotService.find(id)
                .map(AgentReviewResponse::from)
                .orElse(null));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent-review")
    public ApiResponse<AgentReviewResponse> refreshAgentReview(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        UUID id = parseProjectId(projectId);
        var result = agentReviewSnapshotService.refresh(id);
        flowDebugLogger.log("PROCUREMENT_AGENT_REVIEW", "PROCUREMENT", "APPLICATION",
                refresh ? "refresh" : "execute");
        return ApiResponse.success(AgentReviewResponse.from(result));
    }

    @GetMapping("/{projectId}/export")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String projectId) {
        CurrentUser user = currentUser();
        try {
            UUID id = parseProjectId(projectId);
            ProcurementProjectExportService.ExportResult result = projectExportService.export(id);
            operationAudit.success("EXPORT_PROJECT", user, "FILE", id.toString(), result.project().projectName(),
                    null, java.util.Map.of("projectCode", result.project().projectCode()), "导出采购需求");
            String projectCode = safeFilenamePart(result.project().projectCode());
            String filename = "采购需求_" + projectCode + "_"
                    + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
            flowDebugLogger.log("PROCUREMENT_PROJECT_EXPORT", "PROCUREMENT", "WEB", "export");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(filename, StandardCharsets.UTF_8).build().toString())
                    .body(new ByteArrayResource(result.content()));
        } catch (RuntimeException exception) {
            operationAudit.failure("EXPORT_PROJECT", user, "FILE", projectId, null, failureReason(exception));
            throw exception;
        }
    }

    private static UUID parseProjectId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "projectId must be a valid UUID", exception);
        }
    }

    private CurrentUser currentUser() {
        return authContext.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static String failureReason(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String safeFilenamePart(String value) {
        return value == null || value.isBlank()
                ? "未编号" : value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total, long totalPages) {}

    public record ProjectResponse(UUID id, String projectCode, String projectName,
                                  String subjectCode, String subjectName, BigDecimal budgetAmount,
                                  String supplierName, String status, Instant createdTime,
                                  String departmentName, String procurementType, String procurementMethod,
                                  String requirementDescription, String applicantName, String attachmentName,
                                  UUID sourceProjectId, List<UUID> sourceMaterialIds,
                                  String agentReviewStatus, String agentRiskLevel, Instant agentReviewTime) {
        static ProjectResponse from(ProcurementProject project,
                                     ProcurementAgentReviewSnapshotService.ReviewSummary reviewSummary) {
            return new ProjectResponse(project.id(), project.projectCode(), project.projectName(),
                    project.subjectCode(), project.subjectName(), project.budgetAmount(),
                    project.supplierName(), project.status(), project.createdTime(), project.departmentName(),
                    project.procurementType(), project.procurementMethod(), project.requirementDescription(),
                    project.applicantName(), project.attachmentName(), project.sourceProjectId(),
                    project.sourceMaterialIds(),
                    reviewSummary == null ? "NOT_CHECKED" : reviewSummary.status(),
                    reviewSummary == null ? null : reviewSummary.riskLevel(),
                    reviewSummary == null ? null : reviewSummary.reviewTime());
        }

        static ProjectResponse from(ProcurementProject project) {
            return from(project, null);
        }
    }

    public record SourceProjectResponse(UUID id, String name, String projectNo,
                                        String category, Instant updatedAt, String fileName,
                                        com.fuquan.audit.common.material.domain.MaterialIntakeFields fields) {
        static SourceProjectResponse from(com.fuquan.audit.procurement.domain.ProcurementSourceProject project) {
            return new SourceProjectResponse(project.id(), project.name(), project.projectNo(),
                    project.category(), project.updatedAt(), project.fileName(), project.fields());
        }
    }

    public record CreateProjectRequest(
            @NotNull UUID sourceProjectId,
            @jakarta.validation.constraints.Size(max = 100) List<@NotNull UUID> sourceMaterialIds,
            @jakarta.validation.constraints.Size(max = 200) String projectName,
            @NotBlank String departmentName,
            @NotBlank String procurementType,
            @NotNull @DecimalMin(value = "0.01") BigDecimal budgetAmount,
            @NotBlank String subjectCode,
            @NotBlank String subjectName,
            @NotBlank String procurementMethod,
            String requirementDescription,
            @NotBlank String applicantName,
            String attachmentName,
            boolean submitForApproval) {
        List<UUID> materialIds() {
            return sourceMaterialIds == null || sourceMaterialIds.isEmpty()
                    ? List.of(sourceProjectId) : List.copyOf(sourceMaterialIds);
        }
    }

    public record UpdateProjectRequest(
            @NotBlank String projectName,
            @NotBlank String departmentName,
            @NotBlank String procurementType,
            @NotNull @DecimalMin(value = "0.01") BigDecimal budgetAmount,
            @NotBlank String subjectCode,
            @NotBlank String subjectName,
            @NotBlank String procurementMethod,
            String requirementDescription,
            @NotBlank String applicantName,
            String attachmentName) {}

    public record CreateProjectResponse(UUID id) {}

    public record AgentReviewResponse(UUID projectId, String projectCode, String projectName,
                                      String riskLevel, String suggestion, String aiSummary,
                                      Instant generatedTime,
                                      List<AgentReviewItemResponse> items) {
        static AgentReviewResponse from(ProcurementAgentReviewService.ReviewResult result) {
            return new AgentReviewResponse(result.projectId(), result.projectCode(), result.projectName(),
                    result.riskLevel(), result.suggestion(), result.aiSummary(), result.generatedTime(),
                    result.items().stream().map(AgentReviewItemResponse::from).toList());
        }
    }

    public record AgentReviewItemResponse(String checkType, String checkName, String status,
                                          String riskLevel, String detail, String suggestion,
                                          String expectedValue, String actualValue,
                                          String aiExplanation, String aiSuggestion,
                                          boolean needsConfirmation) {
        static AgentReviewItemResponse from(ProcurementAgentReviewService.ReviewItem item) {
            return new AgentReviewItemResponse(item.checkType(), item.checkName(), item.status(),
                    item.riskLevel(), item.detail(), item.suggestion(), item.expectedValue(), item.actualValue(),
                    item.aiExplanation(), item.aiSuggestion(), item.needsConfirmation());
        }
    }
}
