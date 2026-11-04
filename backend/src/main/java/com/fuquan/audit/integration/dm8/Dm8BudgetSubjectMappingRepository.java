package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetSubjectMappingRepository implements BudgetSubjectMappingRepository {

    private final BudgetSubjectMappingMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8BudgetSubjectMappingRepository(BudgetSubjectMappingMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public List<BudgetSubjectMapping> findBySystemSubjectCode(String systemSubjectCode) {
        long startedAt = System.nanoTime();
        List<BudgetSubjectMappingPersistenceRow> rows = mapper.findBySystemSubjectCode(systemSubjectCode);
        flowDebugLogger.log("BUDGET_MAPPING_READ", "BUDGET", "REPOSITORY", "findBySystemSubjectCode",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "count", rows.size()));
        return rows.stream().map(this::toDomain).toList();
    }

    @Override
    public List<BudgetSubjectMapping> findAllActive() {
        long startedAt = System.nanoTime();
        List<BudgetSubjectMappingPersistenceRow> rows = mapper.findAllActive();
        flowDebugLogger.log("BUDGET_MAPPING_READ", "BUDGET", "REPOSITORY", "findAllActive",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "count", rows.size()));
        return rows.stream().map(this::toDomain).toList();
    }

    private BudgetSubjectMapping toDomain(BudgetSubjectMappingPersistenceRow row) {
        return new BudgetSubjectMapping(
                java.util.UUID.fromString(row.getId()),
                row.getSystemSubjectCode(),
                row.getSystemSubjectName(),
                row.getBudgetSubjectCode(),
                row.getBudgetSubjectName(),
                row.getBudgetAmount(),
                row.getBudgetYear(),
                row.getSupplierName(),
                row.getStatus());
    }
}
