package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import java.util.List;

public interface BudgetSplitRiskRuleRepository {
    List<BudgetSplitRiskRule> findActive();
}
