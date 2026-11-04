package com.fuquan.audit.budget.api;

import com.fuquan.audit.budget.application.BudgetApprovalProgramService;
import com.fuquan.audit.budget.application.BudgetApprovalProgramView;
import com.fuquan.audit.budget.application.BudgetApprovalValidationCommand;
import com.fuquan.audit.budget.application.BudgetApprovalValidationPage;
import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/budget/approval-program")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER')")
public class BudgetApprovalProgramController {

    private final BudgetApprovalProgramService service;

    public BudgetApprovalProgramController(BudgetApprovalProgramService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    public ApiResponse<RuleSetResponse> rules() {
        List<BudgetApprovalRule> rules = service.activeRules();
        List<RuleGroupResponse> groups = List.of(
                group(BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT, rules),
                group(BudgetApprovalRuleGroup.AUTHORITY_LEVEL, rules));
        return ApiResponse.success(new RuleSetResponse(BudgetApprovalProgramService.RULE_SET_CODE, groups));
    }

    @PostMapping("/validations")
    public ApiResponse<BudgetApprovalProgramView> validate(
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody ValidationRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.validate(new BudgetApprovalValidationCommand(
                body.taskId(),
                body.itemId(),
                body.actualSteps(),
                idempotencyKey,
                requestId(),
                traceId(),
                request.getRemoteAddr())));
    }

    @GetMapping("/validations")
    public ApiResponse<BudgetApprovalValidationPage> history(
            @RequestParam(required = false) UUID taskId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.history(taskId, page, size));
    }

    private static RuleGroupResponse group(
            BudgetApprovalRuleGroup group,
            List<BudgetApprovalRule> rules) {
        return new RuleGroupResponse(
                group.name(),
                rules.stream()
                        .filter(rule -> rule.ruleGroup() == group)
                        .map(BudgetApprovalProgramController::rule)
                        .toList());
    }

    private static RuleResponse rule(BudgetApprovalRule rule) {
        return new RuleResponse(
                rule.step().code(),
                rule.step().displayName(),
                thresholdLabel(rule),
                rule.minAmount(),
                rule.maxAmount(),
                rule.minInclusive(),
                rule.maxInclusive(),
                rule.stepOrder());
    }

    private static String thresholdLabel(BudgetApprovalRule rule) {
        if (rule.minAmount() == null) {
            return (rule.maxInclusive() ? "≤ " : "< ") + money(rule.maxAmount());
        }
        if (rule.maxAmount() == null) {
            return (rule.minInclusive() ? "≥ " : "> ") + money(rule.minAmount());
        }
        return (rule.minInclusive() ? "≥ " : "> ") + money(rule.minAmount())
                + " 且 " + (rule.maxInclusive() ? "≤ " : "< ") + money(rule.maxAmount());
    }

    private static String money(BigDecimal amount) {
        return "¥" + new DecimalFormat("#,##0.##").format(amount);
    }

    private static String requestId() {
        String value = MDC.get(RequestIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? requestId() : value;
    }

    public record ValidationRequest(
            @NotNull UUID taskId,
            @NotNull UUID itemId,
            @NotNull @Size(max = 20) List<
                    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String> actualSteps) {
    }

    public record RuleSetResponse(String ruleSetCode, List<RuleGroupResponse> ruleGroups) {
    }

    public record RuleGroupResponse(String code, List<RuleResponse> rules) {
    }

    public record RuleResponse(
            String stepCode,
            String stepName,
            String thresholdLabel,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            boolean minInclusive,
            boolean maxInclusive,
            int stepOrder) {
    }
}
