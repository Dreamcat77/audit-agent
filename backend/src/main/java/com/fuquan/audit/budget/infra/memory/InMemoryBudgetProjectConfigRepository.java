package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetProjectConfigRepository implements BudgetProjectConfigRepository {

    private final List<BudgetProjectConfig> configs = LocalBudgetFixtures.projectConfigs();

    @Override
    public List<BudgetProjectConfig> findByProjectCode(String projectCode) {
        return configs.stream()
                .filter(config -> config.projectCode().equalsIgnoreCase(projectCode))
                .filter(config -> "ACTIVE".equalsIgnoreCase(config.status()))
                .toList();
    }

    @Override
    public List<BudgetProjectConfig> findAllActive() {
        return configs.stream()
                .filter(config -> "ACTIVE".equalsIgnoreCase(config.status()))
                .toList();
    }
}
