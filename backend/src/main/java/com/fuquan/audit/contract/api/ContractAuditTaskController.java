package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.contract.application.ContractAuditTaskListService;
import com.fuquan.audit.contract.application.ContractAuditTaskNavigation;
import com.fuquan.audit.contract.application.ContractAuditTaskNavigationService;
import com.fuquan.audit.contract.application.ContractAuditTaskListQuery;
import com.fuquan.audit.contract.application.ContractAuditTaskPage;
import com.fuquan.audit.contract.application.ContractAuditTaskSummary;
import com.fuquan.audit.contract.application.ContractAuditTaskView;
import com.fuquan.audit.contract.application.F02ReviewProgressSummary;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.Project;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

/** Read-only task center for real contract-owned direct F02 audit tasks. */
@RestController
@Validated
@RequestMapping("/api/v1/contract-audit-tasks")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL')")
public class ContractAuditTaskController {

    private final ContractAuditTaskListService service;
    private final ContractAuditTaskNavigationService navigation;

    public ContractAuditTaskController(ContractAuditTaskListService service,
            ContractAuditTaskNavigationService navigation) {
        this.service = service;
        this.navigation = navigation;
    }

    @GetMapping
    public ApiResponse<ContractAuditTaskPageResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(required = false) ContractAuditTaskListQuery.RiskFilter risk,
            @RequestParam(required = false) ContractAuditTaskListQuery.StatusFilter status,
            @RequestParam(defaultValue = "false") boolean includeHistory) {
        return ApiResponse.success(ContractAuditTaskPageResponse.from(service.list(
                new ContractAuditTaskListQuery(page, size, keyword, createdFrom, createdTo, risk, status),
                includeHistory),
                navigation));
    }

    @GetMapping("/summary")
    public ApiResponse<ContractAuditTaskSummary> summary(
            @RequestParam(defaultValue = "false") boolean includeHistory) {
        return ApiResponse.success(service.summary(includeHistory));
    }

    public record ContractAuditTaskPageResponse(
            List<ContractAuditTaskResponse> items,
            int page,
            int size,
            long total,
            long totalPages) {
        static ContractAuditTaskPageResponse from(ContractAuditTaskPage page,
                ContractAuditTaskNavigationService navigation) {
            return new ContractAuditTaskPageResponse(
                    page.items().stream().map(view -> ContractAuditTaskResponse.from(view,
                            navigation.resolveTask(view.task(), view.project() == null
                                    ? null : view.project().id()))).toList(),
                    page.page(), page.size(), page.total(), page.totalPages());
        }
    }

    public record ContractAuditTaskResponse(
            UUID taskId,
            ContractTaskResponse contract,
            ProjectTaskResponse project,
            String taskStatus,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            ReviewProgressResponse reviewProgress,
            RiskResponse risk,
            ContractAuditTaskNavigation reviewRun) {
        static ContractAuditTaskResponse from(ContractAuditTaskView view,
                ContractAuditTaskNavigationService.ResolvedTask resolved) {
            return new ContractAuditTaskResponse(
                    view.task().id(),
                    ContractTaskResponse.from(resolved.frozenContract() == null
                            ? view.contract() : resolved.frozenContract()),
                    view.project() == null ? null : ProjectTaskResponse.from(view.project()),
                    view.task().status().name(),
                    view.task().createdAt(),
                    view.task().startedAt(),
                    view.task().finishedAt(),
                    ReviewProgressResponse.from(view.reviewProgress()),
                    RiskResponse.from(view.reviewProgress()),
                    resolved.navigation());
        }
    }

    public record ContractTaskResponse(
            UUID id,
            String contractNo,
            String contractName,
            String contractType,
            String partyA,
            String partyB,
            String partyBCreditCode,
            BigDecimal contractAmount,
            String amountInWords,
            String status,
            long versionNo,
            Instant createdAt,
            Instant updatedAt) {
        static ContractTaskResponse from(Contract contract) {
            return new ContractTaskResponse(
                    contract.id(), contract.contractNo(), contract.contractName(), contract.contractType(),
                    contract.partyA(), contract.partyB(), contract.partyBCreditCode(),
                    contract.contractAmount().amount(), contract.amountInWords(), contract.status(),
                    contract.versionNo(), contract.createdAt(), contract.updatedAt());
        }
    }

    public record ProjectTaskResponse(UUID projectId, String projectName, String projectNo) {
        static ProjectTaskResponse from(Project project) {
            return new ProjectTaskResponse(project.id(), project.name(), project.projectNo());
        }
    }

    public record ReviewProgressResponse(
            int current,
            int total,
            boolean complete,
            boolean pendingAcknowledgement) {
        static ReviewProgressResponse from(F02ReviewProgressSummary progress) {
            return new ReviewProgressResponse(progress.current(), progress.total(), progress.complete(),
                    progress.pendingAcknowledgement());
        }
    }

    public record RiskResponse(long riskCount, boolean materialInsufficient, String riskSummary) {
        static RiskResponse from(F02ReviewProgressSummary progress) {
            return new RiskResponse(progress.riskCount(), progress.materialInsufficient(), progress.riskSummary());
        }
    }
}
