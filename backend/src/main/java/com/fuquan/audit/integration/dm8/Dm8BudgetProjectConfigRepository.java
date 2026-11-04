package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetProjectConfigRepository implements BudgetProjectConfigRepository {
    private final BudgetProjectConfigMapper mapper;

    public Dm8BudgetProjectConfigRepository(BudgetProjectConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BudgetProjectConfig> findByProjectCode(String projectCode) {
        return mapper.findByProjectCode(projectCode).stream().map(Dm8BudgetProjectConfigRepository::toDomain).toList();
    }

    @Override
    public List<BudgetProjectConfig> findAllActive() {
        return mapper.findAllActive().stream().map(Dm8BudgetProjectConfigRepository::toDomain).toList();
    }

    private static BudgetProjectConfig toDomain(BudgetProjectConfigPersistenceRow row) {
        return new BudgetProjectConfig(
                java.util.UUID.fromString(row.getId()), row.getProjectCode(), row.getProjectName(),
                row.getDepartmentCode(), row.getDepartmentName(), row.getStatus(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
