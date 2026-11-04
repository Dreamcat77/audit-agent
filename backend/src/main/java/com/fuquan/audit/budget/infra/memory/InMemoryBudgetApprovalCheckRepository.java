package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetApprovalCheckRepository;
import com.fuquan.audit.budget.domain.BudgetCheckResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetApprovalCheckRepository implements BudgetApprovalCheckRepository {

    private final List<BudgetCheckResult> stored = new ArrayList<>();

    @Override
    public void saveAll(List<BudgetCheckResult> results) {
        stored.addAll(results);
    }

    /** Test-only accessor. */
    public List<BudgetCheckResult> stored() {
        return Collections.unmodifiableList(stored);
    }
}
