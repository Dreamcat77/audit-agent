package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetSplitRiskAssessment;
import java.util.Optional;
import java.util.UUID;

public interface BudgetSplitRiskAssessmentRepository {
    void create(BudgetSplitRiskAssessment assessment);
    Optional<BudgetSplitRiskAssessment> findById(UUID id);
}
