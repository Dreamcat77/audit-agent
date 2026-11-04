package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import java.util.List;

public interface BudgetProjectConfigRepository {

    default List<BudgetProjectConfig> findByProjectCode(String projectCode) {
        return List.of();
    }

    default List<BudgetProjectConfig> findAllActive() {
        return List.of();
    }
}
