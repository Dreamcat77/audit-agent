package com.fuquan.audit.procurement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Latest F11 split-purchase assessment for one procurement project. */
public record ProcurementSplitPurchaseAssessment(UUID id, UUID projectId, String riskLevel,
        int riskScore, List<Finding> findings, Instant assessedTime) {
    public ProcurementSplitPurchaseAssessment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (!List.of("CRITICAL", "WARNING", "INFO").contains(riskLevel)) {
            throw new IllegalArgumentException("invalid risk level");
        }
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        findings = List.copyOf(findings);
        Objects.requireNonNull(assessedTime, "assessedTime must not be null");
    }

    public record Finding(String supplierName, Instant windowStart, Instant windowEnd,
                          BigDecimal totalAmount, BigDecimal thresholdAmount, String thresholdName,
                          int riskScore, String riskLevel, List<Record> records) {
        public Finding {
            Objects.requireNonNull(supplierName, "supplierName must not be null");
            Objects.requireNonNull(windowStart, "windowStart must not be null");
            Objects.requireNonNull(windowEnd, "windowEnd must not be null");
            Objects.requireNonNull(totalAmount, "totalAmount must not be null");
            Objects.requireNonNull(thresholdAmount, "thresholdAmount must not be null");
            records = List.copyOf(records);
        }
    }

    public record Record(UUID projectId, String projectCode, String projectName,
                         BigDecimal quoteAmount, Instant quoteTime) {
    }
}
