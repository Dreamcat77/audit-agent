package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 采购审查报告元数据及生成时保存的预审快照。 */
public record ProcurementReviewReport(
        UUID id,
        UUID projectId,
        String reportNumber,
        String riskLevel,
        Instant generatedTime,
        String overallConclusion,
        String agentReviewJson,
        String reportSnapshotJson) {

    public ProcurementReviewReport(UUID id,UUID projectId,String reportNumber,String riskLevel,Instant generatedTime,
                                   String overallConclusion,String agentReviewJson) {
        this(id,projectId,reportNumber,riskLevel,generatedTime,overallConclusion,agentReviewJson,null);
    }

    public ProcurementReviewReport(UUID id, UUID projectId, String reportNumber,
                                   String riskLevel, Instant generatedTime) {
        this(id, projectId, reportNumber, riskLevel, generatedTime, null, null, null);
    }

    public ProcurementReviewReport {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (reportNumber == null || reportNumber.isBlank()) {
            throw new IllegalArgumentException("reportNumber must not be blank");
        }
        if (riskLevel == null || !switch (riskLevel) {
            case "CRITICAL", "WARNING", "INFO" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("riskLevel must be CRITICAL, WARNING or INFO");
        }
        Objects.requireNonNull(generatedTime, "generatedTime must not be null");
    }
}
