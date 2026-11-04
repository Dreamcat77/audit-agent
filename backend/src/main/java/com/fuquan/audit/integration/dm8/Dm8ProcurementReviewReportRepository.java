package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementReviewReportRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 采购审查报告元数据 DM8 持久化适配器。 */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementReviewReportRepository implements ProcurementReviewReportRepository {

    private final ProcurementReviewReportMapper mapper;

    public Dm8ProcurementReviewReportRepository(ProcurementReviewReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(ProcurementReviewReport report) {
        ProcurementReviewReportPersistenceRow row = new ProcurementReviewReportPersistenceRow();
        row.setId(report.id().toString());
        row.setProjectId(report.projectId().toString());
        row.setReportNumber(report.reportNumber());
        row.setRiskLevel(report.riskLevel());
        row.setGeneratedTime(LocalDateTime.ofInstant(report.generatedTime(), ZoneOffset.UTC));
        row.setOverallConclusion(report.overallConclusion());
        row.setAgentReviewJson(report.agentReviewJson());
        row.setReportSnapshotJson(report.reportSnapshotJson());
        mapper.insert(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementReviewReport> findLatestByProjectId(UUID projectId) {
        ProcurementReviewReportPersistenceRow row = mapper.findLatestByProjectId(projectId.toString());
        if (row == null) return Optional.empty();
        return Optional.of(new ProcurementReviewReport(UUID.fromString(row.getId()),
                UUID.fromString(row.getProjectId()), row.getReportNumber(), row.getRiskLevel(),
                row.getGeneratedTime().toInstant(ZoneOffset.UTC), row.getOverallConclusion(),
                row.getAgentReviewJson(), row.getReportSnapshotJson()));
    }
}
