package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetCategoryRuleRepository implements BudgetCategoryRuleRepository {

    private final List<BudgetCategoryRule> rules =
            new java.util.concurrent.CopyOnWriteArrayList<>(LocalBudgetFixtures.categoryRules());

    @Override
    public List<BudgetCategoryRule> findByCategoryCode(String categoryCode) {
        return rules.stream()
                .filter(rule -> rule.categoryCode().equalsIgnoreCase(categoryCode))
                .filter(rule -> "ACTIVE".equalsIgnoreCase(rule.status()))
                .toList();
    }

    @Override
    public List<BudgetCategoryRule> findAllActive() {
        return rules.stream()
                .filter(rule -> "ACTIVE".equalsIgnoreCase(rule.status()))
                .toList();
    }
}
