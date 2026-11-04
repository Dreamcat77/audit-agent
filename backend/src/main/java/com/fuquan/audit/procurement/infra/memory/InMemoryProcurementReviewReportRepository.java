package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementReviewReportRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementReviewReportRepository implements ProcurementReviewReportRepository {
    private final ConcurrentMap<UUID, ProcurementReviewReport> reports = new ConcurrentHashMap<>();

    @Override
    public void save(ProcurementReviewReport report) {
        reports.put(report.projectId(), report);
    }

    @Override
    public Optional<ProcurementReviewReport> findLatestByProjectId(UUID projectId) {
        return Optional.ofNullable(reports.get(projectId));
    }
}
