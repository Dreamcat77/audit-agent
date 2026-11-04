package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BlacklistAlertRepository implements BlacklistAlertRepository {

    private final BlacklistAlertMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8BlacklistAlertRepository(
            BlacklistAlertMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    @Transactional
    public void save(BlacklistAlert alert) {
        mapper.insert(toRow(alert));
    }

    @Override
    @Transactional
    public void saveAll(List<BlacklistAlert> alerts) {
        long startedAt = System.nanoTime();
        List<BlacklistAlertPersistenceRow> rows = new ArrayList<>(alerts.size());
        alerts.forEach(alert -> rows.add(toRow(alert)));
        if (!rows.isEmpty()) mapper.insertBatch(rows);
        flowDebugLogger.log("BLACKLIST_ALERT_PERSIST", "BLACKLIST", "REPOSITORY", "saveAll",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "count", rows.size()));
    }

    @Override
    public Optional<BlacklistAlert> findById(UUID id) {
        BlacklistAlertPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<BlacklistAlert> findAll(String status) {
        return mapper.findAll(status).stream().map(this::toDomain).toList();
    }

    @Override
    public List<BlacklistAlert> findPage(
            String batchId, String alertLevel, String status,
            String supplierName, int offset, int limit) {
        return mapper.findPage(batchId, alertLevel, status, supplierName, offset, offset + limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String batchId, String alertLevel, String status, String supplierName) {
        return mapper.count(batchId, alertLevel, status, supplierName);
    }

    @Override
    public boolean existsByBatchAndSupplier(String batchId, String supplierName) {
        return mapper.countByBatchAndSupplier(batchId, supplierName) > 0;
    }

    @Override
    @Transactional
    public AlertCreation saveIfAbsent(BlacklistAlert alert) {
        BlacklistAlertPersistenceRow candidate = toRow(alert);
        DataIntegrityViolationException concurrentInsert = null;
        try {
            mapper.insertIfAbsent(candidate);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent transaction can still reach the unique index after
            // both MERGE statements observed no row. Redis/JVM locks are not a
            // correctness boundary, so rediscover the durable database winner.
            concurrentInsert = exception;
        }
        BlacklistAlertPersistenceRow durable = mapper.findByBatchAndSupplier(
                alert.batchId(), alert.supplierName());
        if (durable == null) {
            if (concurrentInsert != null) {
                throw concurrentInsert;
            }
            throw new IllegalStateException("黑名单告警原子创建结果无法确认");
        }
        boolean created = candidate.getId().equals(durable.getId());
        return new AlertCreation(toDomain(durable), created);
    }

    @Override
    @Transactional
    public boolean updateIfStatus(BlacklistAlert alert, String expectedStatus) {
        return mapper.updateIfStatus(toRow(alert), expectedStatus) == 1;
    }

    private BlacklistAlert toDomain(BlacklistAlertPersistenceRow row) {
        return new BlacklistAlert(
                UUID.fromString(row.getId()),
                row.getBatchId(),
                row.getRowNumber(),
                row.getProcurementId(),
                row.getSupplierName(),
                row.getSupplierCode(),
                UUID.fromString(row.getBlacklistId()),
                row.getMatchedName(),
                row.getBlacklistType(),
                row.getBlacklistReason(),
                row.getMatchType(),
                row.getMatchScore() == null ? 0 : row.getMatchScore().intValue(),
                row.getAlertLevel(),
                row.getStatus(),
                row.getHandler(),
                row.getHandleRemark(),
                toInstant(row.getHandleTime()),
                toInstant(row.getCreatedTime()));
    }

    private BlacklistAlertPersistenceRow toRow(BlacklistAlert domain) {
        BlacklistAlertPersistenceRow row = new BlacklistAlertPersistenceRow();
        row.setId(domain.id().toString());
        row.setBatchId(domain.batchId());
        row.setRowNumber(domain.rowNumber());
        row.setProcurementId(domain.procurementId());
        row.setSupplierName(domain.supplierName());
        row.setSupplierCode(domain.supplierCode());
        row.setBlacklistId(domain.blacklistId().toString());
        row.setMatchedName(domain.matchedName());
        row.setBlacklistType(domain.blacklistType());
        row.setBlacklistReason(domain.blacklistReason());
        row.setMatchType(domain.matchType());
        row.setMatchScore(BigDecimal.valueOf(domain.matchScore()));
        row.setAlertLevel(domain.alertLevel());
        row.setStatus(domain.status());
        row.setHandler(domain.handler());
        row.setHandleRemark(domain.handleRemark());
        row.setHandleTime(toLocalDateTime(domain.handleTime()));
        row.setCreatedTime(toLocalDateTime(domain.createdTime()));
        return row;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
