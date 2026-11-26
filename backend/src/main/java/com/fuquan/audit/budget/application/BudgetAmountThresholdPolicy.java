package com.fuquan.audit.budget.application;

import java.util.Set;

/** Retired built-in amounts are not a substitute for an uploaded approval policy. */
final class BudgetAmountThresholdPolicy {
    private static final Set<String> RETIRED_RULES=Set.of("F05_50K","F05_200K","F05_2M");
    private static final Set<String> RETIRED_CHECKS=Set.of("TOTAL_APPROVAL_THRESHOLD","AUTO_SPLIT_APPLICATION");
    private BudgetAmountThresholdPolicy() {}

    static boolean activeRule(String code) { return code==null || !RETIRED_RULES.contains(code); }

    /** A read projection only: the original historical snapshot remains unchanged. */
    static BudgetUploadedFacts.FileResult current(BudgetUploadedFacts.FileResult saved) {
        var checks=saved.assessments().stream().filter(a->a.code()==null || !RETIRED_CHECKS.contains(a.code())).toList();
        if(checks.size()==saved.assessments().size())return saved;
        return new BudgetUploadedFacts.FileResult(saved.status(),saved.coverage(),saved.summary(),saved.lines(),checks,
                saved.projectName(),saved.totals(),saved.analysisCoverage());
    }
}
