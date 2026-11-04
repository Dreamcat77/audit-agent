package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F03 黑名单 — 报警记录持久化 Port。
 */
public interface BlacklistAlertRepository {

    void save(BlacklistAlert alert);

    void saveAll(List<BlacklistAlert> alerts);

    Optional<BlacklistAlert> findById(UUID id);

    List<BlacklistAlert> findAll(String status);

    default List<BlacklistAlert> findPage(
            String batchId, String alertLevel, String status,
            String supplierName, int offset, int limit) {
        return findAll(status).stream()
                .filter(item -> batchId == null || batchId.isBlank()
                        || batchId.equals(item.batchId()))
                .filter(item -> alertLevel == null || alertLevel.isBlank()
                        || alertLevel.equals(item.alertLevel()))
                .filter(item -> supplierName == null || supplierName.isBlank()
                        || item.supplierName().contains(supplierName))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    default long count(String batchId, String alertLevel, String status, String supplierName) {
        return findPage(batchId, alertLevel, status, supplierName, 0, Integer.MAX_VALUE).size();
    }

    default boolean existsByBatchAndSupplier(String batchId, String supplierName) {
        return findAll(null).stream().anyMatch(item ->
                batchId.equals(item.batchId()) && supplierName.equals(item.supplierName()));
    }

    /** Atomically creates an alert or returns the durable alert for the same operation key. */
    AlertCreation saveIfAbsent(BlacklistAlert alert);

    boolean updateIfStatus(BlacklistAlert alert, String expectedStatus);

    record AlertCreation(BlacklistAlert alert, boolean created) {}
}
