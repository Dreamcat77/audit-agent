package com.fuquan.audit.blacklist.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * F03 黑名单人工操作留痕。
 */
public record BlacklistSupplierOperationLog(
        UUID id,
        UUID blacklistId,
        String operationType,
        String operatorUserId,
        String operatorName,
        String operationReason,
        Instant operatedTime,
        Instant createdTime) {

    public BlacklistSupplierOperationLog {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(blacklistId, "blacklistId must not be null");
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("operationType must not be blank");
        }
        if (operatorUserId == null || operatorUserId.isBlank()) {
            throw new IllegalArgumentException("operatorUserId must not be blank");
        }
        if (operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("operatorName must not be blank");
        }
        if (operationReason == null || operationReason.isBlank()) {
            throw new IllegalArgumentException("operationReason must not be blank");
        }
        Objects.requireNonNull(operatedTime, "operatedTime must not be null");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
    }

    public static BlacklistSupplierOperationLog removed(
            UUID blacklistId, String operatorUserId, String operatorName, String reason) {
        Instant now = Instant.now();
        return new BlacklistSupplierOperationLog(
                UUID.randomUUID(), blacklistId, "REMOVE", operatorUserId, operatorName,
                reason, now, now);
    }
}
