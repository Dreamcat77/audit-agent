package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import java.util.List;

public interface BudgetApprovalRuleRepository {
    List<BudgetApprovalRule> findActiveByRuleSetCode(String ruleSetCode);

    List<BudgetApprovalRule> findByRuleSetCode(String ruleSetCode);
}
