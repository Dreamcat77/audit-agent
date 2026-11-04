package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetYearValidityConfigRepository implements BudgetYearValidityConfigRepository {
    private final BudgetYearValidityConfigMapper mapper;

    public Dm8BudgetYearValidityConfigRepository(BudgetYearValidityConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BudgetYearValidityConfig> findBySubjectCode(String subjectCode) {
        return mapper.findBySubjectCode(subjectCode).stream()
                .map(Dm8BudgetYearValidityConfigRepository::toDomain).toList();
    }

    @Override
    public List<BudgetYearValidityConfig> findAllActive() {
        return mapper.findAllActive().stream()
                .map(Dm8BudgetYearValidityConfigRepository::toDomain).toList();
    }

    private static BudgetYearValidityConfig toDomain(BudgetYearValidityConfigPersistenceRow row) {
        return new BudgetYearValidityConfig(
                java.util.UUID.fromString(row.getId()), row.getSubjectCode(),
                row.getStartYear(), row.getEndYear(), row.getStatus(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
