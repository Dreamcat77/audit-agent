package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import java.util.List;
import java.util.UUID;

/**
 * F03 黑名单人工操作日志持久化 Port。
 */
public interface BlacklistSupplierOperationLogRepository {

    void save(BlacklistSupplierOperationLog log);

    List<BlacklistSupplierOperationLog> findByBlacklistId(UUID blacklistId);
}
