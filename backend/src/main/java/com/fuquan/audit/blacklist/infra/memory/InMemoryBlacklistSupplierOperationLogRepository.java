package com.fuquan.audit.blacklist.infra.memory;

import com.fuquan.audit.blacklist.application.port.BlacklistSupplierOperationLogRepository;
import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBlacklistSupplierOperationLogRepository
        implements BlacklistSupplierOperationLogRepository {

    private final List<BlacklistSupplierOperationLog> stored = new ArrayList<>();

    @Override
    public void save(BlacklistSupplierOperationLog log) {
        stored.add(log);
    }

    @Override
    public List<BlacklistSupplierOperationLog> findByBlacklistId(UUID blacklistId) {
        return stored.stream()
                .filter(item -> item.blacklistId().equals(blacklistId))
                .toList();
    }

    public List<BlacklistSupplierOperationLog> stored() {
        return Collections.unmodifiableList(stored);
    }
}
