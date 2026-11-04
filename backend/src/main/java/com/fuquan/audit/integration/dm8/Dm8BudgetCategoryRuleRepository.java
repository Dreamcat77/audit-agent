package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetCategoryRuleRepository implements BudgetCategoryRuleRepository {

    private final BudgetCategoryRuleMapper mapper;

    public Dm8BudgetCategoryRuleRepository(BudgetCategoryRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BudgetCategoryRule> findByCategoryCode(String categoryCode) {
        return mapper.findByCategoryCode(categoryCode).stream()
                .map(Dm8BudgetCategoryRuleRepository::toDomain)
                .toList();
    }

    @Override
    public List<BudgetCategoryRule> findAllActive() {
        return mapper.findAllActive().stream()
                .map(Dm8BudgetCategoryRuleRepository::toDomain)
                .toList();
    }

    private static BudgetCategoryRule toDomain(BudgetCategoryRulePersistenceRow row) {
        return new BudgetCategoryRule(
                java.util.UUID.fromString(row.getId()),
                row.getCategoryCode(),
                row.getCategoryName(),
                row.getAllowedKeywords(),
                row.getForbiddenKeywords(),
                row.getSuggestCategory(),
                row.getStatus(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
