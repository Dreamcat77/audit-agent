package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import java.time.Instant;
import java.util.UUID;

/** 全局合同内审任务列表的一行投影。 */
public record ReviewSessionTaskSummary(
        UUID sessionId,
        String name,
        String reviewType,
        ReviewSessionStatus status,
        String remark,
        long contractVersion,
        String createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt,
        Instant materialsConfirmedAt,
        long versionNo,
        Instant deletedAt,
        String deletedBy,
        ReviewSessionContractSummary contract) {
}
