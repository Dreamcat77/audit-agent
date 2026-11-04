package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import java.util.List;

public interface BudgetCategoryRuleRepository {

    default List<BudgetCategoryRule> findByCategoryCode(String categoryCode) {
        return List.of();
    }

    default List<BudgetCategoryRule> findAllActive() {
        return List.of();
    }
}
