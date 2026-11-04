package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class BudgetApprovalPolicyEvaluator {

    public BudgetApprovalEvaluation evaluate(
            BigDecimal amount,
            List<BudgetApprovalRule> activeRules,
            List<String> actualSteps) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("declared amount must be a non-negative number");
        }
        List<BudgetApprovalRule> rules = List.copyOf(activeRules);
        List<String> actual = actualSteps == null ? List.of() : List.copyOf(actualSteps);
        validateActualSteps(rules, actual);

        List<BudgetApprovalRule> procedureRules = rules.stream()
                .filter(rule -> rule.ruleGroup() == BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT)
                .filter(rule -> rule.matches(amount))
                .sorted(Comparator.comparingInt(BudgetApprovalRule::stepOrder))
                .toList();
        List<String> requiredProcedure = procedureRules.stream()
                .map(rule -> rule.step().code())
                .toList();
        List<String> missingProcedure = requiredProcedure.stream()
                .filter(code -> !actual.contains(code))
                .toList();
        List<String> presentRequiredProcedure = requiredProcedure.stream()
                .filter(actual::contains)
                .toList();
        boolean procedureOrderValid = isOrderedSubsequence(presentRequiredProcedure, actual);

        List<BudgetApprovalRule> authorityMatches = rules.stream()
                .filter(rule -> rule.ruleGroup() == BudgetApprovalRuleGroup.AUTHORITY_LEVEL)
                .filter(rule -> rule.matches(amount))
                .toList();
        if (authorityMatches.size() != 1) {
            throw new IllegalStateException("authority configuration must match exactly one rule");
        }
        String requiredAuthority = authorityMatches.get(0).step().code();
        boolean authorityMatched = actual.contains(requiredAuthority);
        boolean passed = missingProcedure.isEmpty() && procedureOrderValid && authorityMatched;

        Map<String, BudgetApprovalRule> rulesByStep = rules.stream().collect(Collectors.toMap(
                rule -> rule.step().code(), Function.identity(), (first, ignored) -> first));
        return new BudgetApprovalEvaluation(
                requiredProcedure,
                requiredAuthority,
                actual,
                missingProcedure,
                procedureOrderValid,
                authorityMatched,
                passed,
                summary(passed, missingProcedure, procedureOrderValid, authorityMatched, rulesByStep));
    }

    private static void validateActualSteps(List<BudgetApprovalRule> rules, List<String> actualSteps) {
        Set<String> known = rules.stream().map(rule -> rule.step().code()).collect(Collectors.toSet());
        Set<String> encountered = new HashSet<>();
        for (String code : actualSteps) {
            if (code == null || code.isBlank() || !known.contains(code)) {
                throw new IllegalArgumentException("unknown approval step: " + code);
            }
            if (!encountered.add(code)) {
                throw new IllegalArgumentException("duplicate approval step: " + code);
            }
        }
    }

    private static boolean isOrderedSubsequence(List<String> required, List<String> actual) {
        int requiredIndex = 0;
        for (String code : actual) {
            if (requiredIndex < required.size() && required.get(requiredIndex).equals(code)) {
                requiredIndex++;
            }
        }
        return requiredIndex == required.size();
    }

    private static String summary(
            boolean passed,
            List<String> missing,
            boolean orderValid,
            boolean authorityMatched,
            Map<String, BudgetApprovalRule> rulesByStep) {
        if (passed) {
            return "审批程序符合当前规则集";
        }
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("缺少审批环节：" + missing.stream()
                    .map(code -> rulesByStep.get(code).step().displayName())
                    .collect(Collectors.joining("、")));
        }
        if (!orderValid) {
            problems.add("审批环节顺序不符合当前规则");
        }
        if (!authorityMatched) {
            problems.add("审批权限层级不符合当前规则");
        }
        return String.join("；", problems);
    }
}
