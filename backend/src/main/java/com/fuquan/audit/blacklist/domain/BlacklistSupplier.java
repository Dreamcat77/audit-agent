package com.fuquan.audit.blacklist.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * F03 黑名单主数据。
 */
public record BlacklistSupplier(
        UUID id,
        String supplierName,
        String supplierCode,
        String blacklistType,
        String reason,
        String source,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        boolean active,
        String createdBy,
        Instant createdTime,
        Instant updatedTime) {

    public BlacklistSupplier {
        Objects.requireNonNull(id, "id must not be null");
        if (supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("supplierName must not be blank");
        }
        if (blacklistType == null || blacklistType.isBlank()) {
            throw new IllegalArgumentException("blacklistType must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        if (expiryDate != null && expiryDate.isBefore(effectiveDate)) {
            throw new IllegalArgumentException("expiryDate must not be before effectiveDate");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy must not be blank");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }

    public static BlacklistSupplier create(
            String supplierName,
            String supplierCode,
            String blacklistType,
            String reason,
            String source,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            String createdBy) {
        Instant now = Instant.now();
        return new BlacklistSupplier(
                UUID.randomUUID(), supplierName, supplierCode, blacklistType,
                reason != null && !reason.isBlank() ? reason : "未说明",
                source != null && !source.isBlank() ? source : "内部录入",
                effectiveDate != null ? effectiveDate : LocalDate.now(),
                null, true,
                createdBy != null && !createdBy.isBlank() ? createdBy : "system",
                now, now);
    }

    /**
     * 兼容早期 F03 条目构造方式。
     */
    public static BlacklistSupplier create(String supplierName, String creditCode, String reason) {
        return create(supplierName, creditCode, "本单位黑名单", reason,
                "内部录入", LocalDate.now(), null, "system");
    }

    public String creditCode() {
        return supplierCode;
    }

    public String status() {
        return active ? "ACTIVE" : "INACTIVE";
    }

    public BlacklistSupplier update(
            String supplierName,
            String supplierCode,
            String blacklistType,
            String reason,
            String source,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            String updatedBy) {
        return new BlacklistSupplier(
                id, supplierName, supplierCode, blacklistType, reason, source,
                effectiveDate, null, active,
                updatedBy != null && !updatedBy.isBlank() ? updatedBy : createdBy,
                createdTime, Instant.now());
    }

    public BlacklistSupplier deactivate() {
        return new BlacklistSupplier(
                id, supplierName, supplierCode, blacklistType, reason, source,
                effectiveDate, expiryDate, false, createdBy, createdTime, Instant.now());
    }

    public boolean effectiveOn(LocalDate date) {
        // 黑名单入库后长期有效，是否生效只由人工停用状态决定。
        return active;
    }
}
