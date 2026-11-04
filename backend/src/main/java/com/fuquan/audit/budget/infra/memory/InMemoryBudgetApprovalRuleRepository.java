package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.BudgetApprovalProgramService;
import com.fuquan.audit.budget.application.port.BudgetApprovalRuleRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetApprovalRuleRepository implements BudgetApprovalRuleRepository {

    private final List<BudgetApprovalRule> rules = List.of(
            rule("40000000-0000-0000-0000-000000000001", BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT,
                    "CUMULATIVE_MIN", "10000.00", null, true, false,
                    "REQUEST_APPROVAL", "请示报批", 10),
            rule("40000000-0000-0000-0000-000000000002", BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT,
                    "CUMULATIVE_MIN", "50000.00", null, true, false,
                    "GENERAL_BRANCH_REVIEW", "总支会审议", 20),
            rule("40000000-0000-0000-0000-000000000003", BudgetApprovalRuleGroup.AUTHORITY_LEVEL,
                    "RANGE", null, "200000.00", false, false,
                    "FINANCE_REVIEW", "财处审批", 10),
            rule("40000000-0000-0000-0000-000000000004", BudgetApprovalRuleGroup.AUTHORITY_LEVEL,
                    "RANGE", "200000.00", "2000000.00", true, false,
                    "MUNICIPAL_LEADER_REVIEW", "市局领导", 20),
            rule("40000000-0000-0000-0000-000000000005", BudgetApprovalRuleGroup.AUTHORITY_LEVEL,
                    "RANGE", "2000000.00", null, true, false,
                    "PARTY_COMMITTEE_REVIEW", "党委会", 30));

    @Override
    public List<BudgetApprovalRule> findActiveByRuleSetCode(String ruleSetCode) {
        return findByRuleSetCode(ruleSetCode).stream()
                .filter(rule -> rule.ruleSetCode().equals(ruleSetCode))
                .filter(rule -> "ACTIVE".equals(rule.status()))
                .toList();
    }

    @Override
    public List<BudgetApprovalRule> findByRuleSetCode(String ruleSetCode) {
        return rules.stream()
                .filter(rule -> rule.ruleSetCode().equals(ruleSetCode))
                .toList();
    }

    private static BudgetApprovalRule rule(
            String id,
            BudgetApprovalRuleGroup group,
            String matchMode,
            String min,
            String max,
            boolean minInclusive,
            boolean maxInclusive,
            String stepCode,
            String stepName,
            int order) {
        return new BudgetApprovalRule(
                UUID.fromString(id),
                BudgetApprovalProgramService.RULE_SET_CODE,
                group,
                matchMode,
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                minInclusive,
                maxInclusive,
                new BudgetApprovalStep(stepCode, stepName),
                order,
                "ACTIVE",
                0);
    }
}
