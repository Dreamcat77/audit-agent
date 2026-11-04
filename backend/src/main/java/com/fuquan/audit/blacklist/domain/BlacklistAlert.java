package com.fuquan.audit.blacklist.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F03 黑名单命中报警事实。
 */
public record BlacklistAlert(
        UUID id,
        String batchId,
        Integer rowNumber,
        String procurementId,
        String supplierName,
        String supplierCode,
        UUID blacklistId,
        String matchedName,
        String blacklistType,
        String blacklistReason,
        String matchType,
        int matchScore,
        String alertLevel,
        String status,
        String handler,
        String handleRemark,
        Instant handleTime,
        Instant createdTime) {

    public BlacklistAlert {
        Objects.requireNonNull(id, "id must not be null");
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        if (supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("supplierName must not be blank");
        }
        if (blacklistId == null) {
            throw new IllegalArgumentException("blacklistId must not be null");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(alertLevel, "alertLevel must not be null");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
    }

    public static BlacklistAlert create(
            String batchId,
            Integer rowNumber,
            String procurementId,
            String supplierName,
            String supplierCode,
            BlacklistSupplier supplier,
            String matchType,
            int matchScore,
            String alertLevel,
            boolean autoResolve) {
        Instant now = Instant.now();
        return new BlacklistAlert(
                UUID.randomUUID(), batchId, rowNumber, procurementId, supplierName, supplierCode,
                supplier.id(), supplier.supplierName(), supplier.blacklistType(), supplier.reason(),
                matchType, matchScore, alertLevel, autoResolve ? "RESOLVED" : "PENDING",
                autoResolve ? "system" : null, null, autoResolve ? now : null, now);
    }

    /**
     * 兼容早期仅使用采购编号的检查入口。
     */
    public static BlacklistAlert create(
            String procurementId, String supplierName,
            String matchedName, String matchType, int matchScore) {
        return new BlacklistAlert(
                UUID.randomUUID(),
                procurementId != null && !procurementId.isBlank() ? procurementId : "MANUAL",
                null, procurementId, supplierName, null, UUID.randomUUID(), matchedName,
                "本单位黑名单", "历史命中记录", matchType, matchScore, "WARNING",
                "PENDING", null, null, null, Instant.now());
    }

    public String batchIdOrProcurementId() {
        return batchId != null ? batchId : procurementId;
    }

    public String resolvedBy() {
        return handler;
    }

    public Instant resolvedTime() {
        return handleTime;
    }

    public String remark() {
        return handleRemark;
    }

    public BlacklistAlert process(String handler, String remark) {
        return withStatus("PROCESSED", handler, remark);
    }

    public BlacklistAlert ignore(String handler, String remark) {
        return withStatus("IGNORED", handler, remark);
    }

    public BlacklistAlert falsePositive(String handler, String remark) {
        return withStatus("FALSE_POSITIVE", handler, remark);
    }

    private BlacklistAlert withStatus(String nextStatus, String handler, String remark) {
        return new BlacklistAlert(
                id, batchId, rowNumber, procurementId, supplierName, supplierCode, blacklistId,
                matchedName, blacklistType, blacklistReason, matchType, matchScore, alertLevel,
                nextStatus, handler, remark, Instant.now(), createdTime);
    }
}
