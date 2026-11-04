package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.ProcurementDemandRepository;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementDemandRepository implements ProcurementDemandRepository {

    private final ProcurementDemandMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8ProcurementDemandRepository(ProcurementDemandMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public Optional<ProcurementDemand> findById(UUID id) {
        long startedAt = System.nanoTime();
        ProcurementDemandPersistenceRow row = mapper.findById(id.toString());
        flowDebugLogger.log("PROCUREMENT_DEMAND_READ", "BUDGET", "REPOSITORY", "findById",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "outcome", row == null ? "NOT_FOUND" : "FOUND"));
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(row));
    }

    @Override
    public List<ProcurementDemand> findAllByIds(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        long startedAt = System.nanoTime();
        List<ProcurementDemand> results = mapper.findByIds(ids.stream().map(UUID::toString).toList())
                .stream().map(this::toDomain).toList();
        flowDebugLogger.log("PROCUREMENT_DEMAND_BATCH_READ", "BUDGET", "REPOSITORY", "findAllByIds",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "requestedCount", ids.size(), "resultCount", results.size()));
        return results;
    }

    private ProcurementDemand toDomain(ProcurementDemandPersistenceRow row) {
        return new ProcurementDemand(
                UUID.fromString(row.getId()),
                row.getProcurementName(),
                row.getBudgetApprovalId(),
                row.getSubjectCode(),
                row.getSubjectName(),
                row.getAmount(),
                row.getSupplierName(),
                row.getStatus(),
                row.getCreatedTime() != null
                        ? row.getCreatedTime().toInstant(ZoneOffset.UTC)
                        : null);
    }
}
