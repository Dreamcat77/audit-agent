package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 使用的临时结果存储；正式环境由 DM8 Adapter 提供事实源。 */
@Repository
@Profile("local")
public class InMemoryProcurementQuoteCheckRepository implements ProcurementQuoteCheckRepository {
    private final ConcurrentMap<String, ProcurementQuoteCheck> checks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProcurementQuoteCheck> byId = new ConcurrentHashMap<>();
    private final ProcurementProjectQueryPort projectQueryPort;

    public InMemoryProcurementQuoteCheckRepository(ProcurementProjectQueryPort projectQueryPort) {
        this.projectQueryPort = projectQueryPort;
    }

    @Override
    public void save(ProcurementQuoteCheck check) {
        if (byId.putIfAbsent(check.id(), check) != null) throw new org.springframework.dao.DuplicateKeyException("quote operation already saved");
        checks.put(check.batchId(), check);
    }

    @Override
    public Optional<ProcurementQuoteCheck> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }

    @Override
    public Optional<ProcurementQuoteCheck> findByBatchId(String batchId) {
        return Optional.ofNullable(checks.get(batchId));
    }

    @Override
    public List<SupplierQuoteTimeline> findLatestSupplierQuotesBySubject(
            String subjectCode, Instant fiscalYearStart, Instant fiscalYearEnd) {
        return checks.values().stream()
                .filter(check -> !check.createdTime().isBefore(fiscalYearStart)
                        && check.createdTime().isBefore(fiscalYearEnd))
                .flatMap(check -> {
                    UUID projectId;
                    try {
                        projectId = UUID.fromString(check.batchId());
                    } catch (IllegalArgumentException ignored) {
                        return java.util.stream.Stream.empty();
                    }
                    return projectQueryPort.findById(projectId)
                            .filter(project -> subjectCode.equals(project.subjectCode())
                                    && project.procurementMethod() != null
                                    && !"公开招标".equals(project.procurementMethod()))
                            .stream()
                            .flatMap(project -> check.quotes().stream().map(quote -> new SupplierQuoteTimeline(
                                    project.id(), project.projectCode(), project.projectName(), quote.supplierName(),
                                    quote.quoteAmount(), check.createdTime())));
                })
                .toList();
    }
}
