package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.UUID;

/** 每个采购项目只保留一份最新的智能体预审快照。 */
public record ProcurementAgentReviewSnapshot(
        UUID id,
        UUID projectId,
        Instant generatedTime,
        String reviewJson) {
}
