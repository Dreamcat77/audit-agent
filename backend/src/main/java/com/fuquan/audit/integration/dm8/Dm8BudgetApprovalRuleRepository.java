package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetApprovalRuleRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetApprovalRuleRepository implements BudgetApprovalRuleRepository {

    private final BudgetApprovalRuleMapper mapper;

    public Dm8BudgetApprovalRuleRepository(BudgetApprovalRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetApprovalRule> findActiveByRuleSetCode(String ruleSetCode) {
        return mapper.findActiveByRuleSetCode(ruleSetCode).stream()
                .map(Dm8BudgetApprovalRuleRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetApprovalRule> findByRuleSetCode(String ruleSetCode) {
        return mapper.findByRuleSetCode(ruleSetCode).stream()
                .map(Dm8BudgetApprovalRuleRepository::toDomain)
                .toList();
    }

    private static BudgetApprovalRule toDomain(BudgetApprovalRulePersistenceRow row) {
        return new BudgetApprovalRule(
                UUID.fromString(row.id()),
                row.ruleSetCode(),
                BudgetApprovalRuleGroup.valueOf(row.ruleGroupCode()),
                row.matchMode(),
                row.minAmount(),
                row.maxAmount(),
                Integer.valueOf(1).equals(row.minInclusive()),
                Integer.valueOf(1).equals(row.maxInclusive()),
                new BudgetApprovalStep(row.stepCode(), row.stepName()),
                row.stepOrder(),
                row.status(),
                row.versionNo());
    }
}
