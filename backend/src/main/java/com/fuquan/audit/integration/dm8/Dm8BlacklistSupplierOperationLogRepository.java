package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.blacklist.application.port.BlacklistSupplierOperationLogRepository;
import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BlacklistSupplierOperationLogRepository
        implements BlacklistSupplierOperationLogRepository {

    private final BlacklistSupplierOperationLogMapper mapper;

    public Dm8BlacklistSupplierOperationLogRepository(BlacklistSupplierOperationLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(BlacklistSupplierOperationLog log) {
        mapper.insert(toRow(log));
    }

    @Override
    public List<BlacklistSupplierOperationLog> findByBlacklistId(UUID blacklistId) {
        return mapper.findByBlacklistId(blacklistId.toString()).stream()
                .map(Dm8BlacklistSupplierOperationLogRepository::toDomain)
                .toList();
    }

    private static BlacklistSupplierOperationLog toDomain(
            BlacklistSupplierOperationLogPersistenceRow row) {
        return new BlacklistSupplierOperationLog(
                UUID.fromString(row.getId()),
                UUID.fromString(row.getBlacklistId()),
                row.getOperationType(),
                row.getOperatorUserId(),
                row.getOperatorName(),
                row.getOperationReason(),
                toInstant(row.getOperatedTime()),
                toInstant(row.getCreatedTime()));
    }

    private static BlacklistSupplierOperationLogPersistenceRow toRow(
            BlacklistSupplierOperationLog domain) {
        BlacklistSupplierOperationLogPersistenceRow row =
                new BlacklistSupplierOperationLogPersistenceRow();
        row.setId(domain.id().toString());
        row.setBlacklistId(domain.blacklistId().toString());
        row.setOperationType(domain.operationType());
        row.setOperatorUserId(domain.operatorUserId());
        row.setOperatorName(domain.operatorName());
        row.setOperationReason(domain.operationReason());
        row.setOperatedTime(toLocalDateTime(domain.operatedTime()));
        row.setCreatedTime(toLocalDateTime(domain.createdTime()));
        return row;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? LocalDateTime.now() : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
