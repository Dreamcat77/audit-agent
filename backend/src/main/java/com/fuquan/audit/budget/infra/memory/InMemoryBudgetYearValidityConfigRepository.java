package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetYearValidityConfigRepository implements BudgetYearValidityConfigRepository {

    private final List<BudgetYearValidityConfig> configs = LocalBudgetFixtures.yearValidityConfigs();

    @Override
    public List<BudgetYearValidityConfig> findBySubjectCode(String subjectCode) {
        return configs.stream()
                .filter(config -> config.subjectCode().equalsIgnoreCase(subjectCode))
                .filter(config -> "ACTIVE".equalsIgnoreCase(config.status()))
                .toList();
    }

    @Override
    public List<BudgetYearValidityConfig> findAllActive() {
        return configs.stream()
                .filter(config -> "ACTIVE".equalsIgnoreCase(config.status()))
                .toList();
    }
}
