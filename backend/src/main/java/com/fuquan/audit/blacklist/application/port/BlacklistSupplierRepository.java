package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F03 黑名单 — 黑名单主数据持久化 Port。
 */
public interface BlacklistSupplierRepository {

    List<BlacklistSupplier> findAllActive();

    /**
     * 返回全部条目，包括人工移除后保留的历史记录。
     */
    default List<BlacklistSupplier> findAll() {
        return findAllActive();
    }

    default Optional<BlacklistSupplier> findById(UUID id) {
        return findAll().stream().filter(item -> item.id().equals(id)).findFirst();
    }

    default List<BlacklistSupplier> findPage(
            String keyword, String blacklistType, Boolean active, int offset, int limit) {
        return findAll().stream()
                .filter(item -> keyword == null || keyword.isBlank()
                        || item.supplierName().contains(keyword)
                        || (item.supplierCode() != null && item.supplierCode().contains(keyword)))
                .filter(item -> blacklistType == null || blacklistType.isBlank()
                        || blacklistType.equals(item.blacklistType()))
                .filter(item -> active == null || active == item.active())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    default long count(String keyword, String blacklistType, Boolean active) {
        return findPage(keyword, blacklistType, active, 0, Integer.MAX_VALUE).size();
    }

    BlacklistSupplier save(BlacklistSupplier supplier);

    default BlacklistSupplier update(BlacklistSupplier supplier) {
        return save(supplier);
    }

    void deleteById(UUID id);
}
