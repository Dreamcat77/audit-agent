package com.fuquan.audit.budget.api;

import com.fuquan.audit.budget.application.BudgetSplitRiskCommand;
import com.fuquan.audit.budget.application.BudgetSplitRiskService;
import com.fuquan.audit.budget.application.BudgetSplitRiskView;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@RestController
@Validated
@RequestMapping("/api/v1/budget/split-risk")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER')")
public class BudgetSplitRiskController {

    private final BudgetSplitRiskService service;

    public BudgetSplitRiskController(BudgetSplitRiskService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ApiResponse<BudgetSplitRiskView> check(
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody CheckRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.check(new BudgetSplitRiskCommand(
                body.procurementIds(), idempotencyKey, requestId(), traceId(), request.getRemoteAddr())));
    }

    @GetMapping("/assessments/{assessmentId}")
    public ApiResponse<BudgetSplitRiskView> get(@PathVariable UUID assessmentId) {
        return ApiResponse.success(service.get(assessmentId));
    }

    public record CheckRequest(
            @NotNull @Size(min = 2, max = 100) List<@NotNull UUID> procurementIds) {
    }

    private static String requestId() {
        String value = MDC.get(RequestIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? requestId() : value;
    }
}
