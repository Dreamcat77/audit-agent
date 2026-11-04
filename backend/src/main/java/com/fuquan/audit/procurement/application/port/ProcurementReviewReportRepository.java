package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import java.util.Optional;
import java.util.UUID;

/** 采购审查报告元数据持久化 Port。 */
public interface ProcurementReviewReportRepository {

    void save(ProcurementReviewReport report);

    Optional<ProcurementReviewReport> findLatestByProjectId(UUID projectId);
}
