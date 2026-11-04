package com.fuquan.audit.procurement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Latest deterministic F11 collusion assessment for one procurement project. */
public record ProcurementCollusionAssessment(UUID id, UUID projectId, int riskScore, String riskLevel,
        BigDecimal quoteCoefficientOfVariation, BigDecimal maxDocumentSimilarity, int duplicateIpGroups,
        int duplicateHandlerGroups, List<PairSimilarity> similarities, Instant assessedTime) {
    public ProcurementCollusionAssessment {
        Objects.requireNonNull(id, "id must not be null"); Objects.requireNonNull(projectId, "projectId must not be null");
        if (riskScore < 0 || riskScore > 100) throw new IllegalArgumentException("riskScore must be between 0 and 100");
        if (!"CRITICAL".equals(riskLevel) && !"WARNING".equals(riskLevel) && !"INFO".equals(riskLevel)) throw new IllegalArgumentException("invalid riskLevel");
        similarities = List.copyOf(similarities); Objects.requireNonNull(assessedTime, "assessedTime must not be null");
    }
    public record PairSimilarity(UUID documentAId, UUID documentBId, String bidderA, String bidderB,
                                 BigDecimal similarity) {}
}
