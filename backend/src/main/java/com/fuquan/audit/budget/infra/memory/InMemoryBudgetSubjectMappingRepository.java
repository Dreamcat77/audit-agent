package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetSubjectMappingRepository implements BudgetSubjectMappingRepository {

    private final List<BudgetSubjectMapping> mappings = LocalBudgetFixtures.budgetSubjectMappings();

    @Override
    public List<BudgetSubjectMapping> findBySystemSubjectCode(String systemSubjectCode) {
        return mappings.stream()
                .filter(m -> m.systemSubjectCode().equals(systemSubjectCode))
                .toList();
    }

    @Override
    public List<BudgetSubjectMapping> findAllActive() {
        return mappings.stream()
                .filter(mapping -> "ACTIVE".equalsIgnoreCase(mapping.status()))
                .toList();
    }
}
