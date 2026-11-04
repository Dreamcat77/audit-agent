package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetSplitRiskAssessmentRepository;
import com.fuquan.audit.budget.application.port.BudgetSplitRiskRuleRepository;
import com.fuquan.audit.budget.domain.BudgetSplitRiskAssessment;
import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetSplitRiskRepository
        implements BudgetSplitRiskRuleRepository, BudgetSplitRiskAssessmentRepository {

    private final ConcurrentMap<UUID, BudgetSplitRiskAssessment> assessments = new ConcurrentHashMap<>();
    private final List<BudgetSplitRiskRule> rules = List.of(
            rule("F05_50K", "5万元审批阈值拆分", "50000.00"),
            rule("F05_200K", "20万元审批阈值拆分", "200000.00"),
            rule("F05_2M", "200万元审批阈值拆分", "2000000.00"));

    @Override
    public List<BudgetSplitRiskRule> findActive() {
        return rules;
    }

    @Override
    public void create(BudgetSplitRiskAssessment assessment) {
        if (assessments.putIfAbsent(assessment.id(), assessment) != null) {
            throw new IllegalStateException("F05 assessment already exists: " + assessment.id());
        }
    }

    @Override
    public Optional<BudgetSplitRiskAssessment> findById(UUID id) {
        return Optional.ofNullable(assessments.get(id));
    }

    private static BudgetSplitRiskRule rule(String code, String name, String amount) {
        BigDecimal threshold = new BigDecimal(amount);
        return new BudgetSplitRiskRule(code, name, 30, threshold, threshold, 2, 60, 85, 1);
    }
}
