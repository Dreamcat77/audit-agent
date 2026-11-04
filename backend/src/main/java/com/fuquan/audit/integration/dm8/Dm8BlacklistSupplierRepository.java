package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BlacklistSupplierRepository implements BlacklistSupplierRepository {

    private final BlacklistSupplierMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8BlacklistSupplierRepository(
            BlacklistSupplierMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public List<BlacklistSupplier> findAllActive() {
        return mapper.findAllActive().stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<BlacklistSupplier> findById(UUID id) {
        BlacklistSupplierPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<BlacklistSupplier> findPage(
            String keyword, String blacklistType, Boolean active, int offset, int limit) {
        return mapper.findPage(keyword, blacklistType, active, offset, offset + limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String keyword, String blacklistType, Boolean active) {
        return mapper.count(keyword, blacklistType, active);
    }

    @Override
    @Transactional
    public BlacklistSupplier save(BlacklistSupplier supplier) {
        mapper.insert(toRow(supplier));
        return supplier;
    }

    @Override
    @Transactional
    public BlacklistSupplier update(BlacklistSupplier supplier) {
        mapper.update(toRow(supplier));
        return supplier;
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        findById(id).ifPresent(item -> mapper.update(toRow(item.deactivate())));
    }

    private BlacklistSupplier toDomain(BlacklistSupplierPersistenceRow row) {
        return new BlacklistSupplier(
                UUID.fromString(row.getId()),
                row.getSupplierName(),
                row.getSupplierCode(),
                row.getBlacklistType(),
                row.getReason(),
                row.getSource(),
                row.getEffectiveDate(),
                row.getExpiryDate(),
                row.getActive() == null || row.getActive(),
                row.getCreatedBy(),
                toInstant(row.getCreatedTime()),
                toInstant(row.getUpdatedTime()));
    }

    private BlacklistSupplierPersistenceRow toRow(BlacklistSupplier domain) {
        BlacklistSupplierPersistenceRow row = new BlacklistSupplierPersistenceRow();
        row.setId(domain.id().toString());
        row.setSupplierName(domain.supplierName());
        row.setSupplierCode(domain.supplierCode());
        row.setBlacklistType(domain.blacklistType());
        row.setReason(domain.reason());
        row.setSource(domain.source());
        row.setEffectiveDate(domain.effectiveDate());
        row.setExpiryDate(domain.expiryDate());
        row.setActive(domain.active());
        row.setCreatedBy(domain.createdBy());
        row.setCreatedTime(toLocalDateTime(domain.createdTime()));
        row.setUpdatedTime(toLocalDateTime(domain.updatedTime()));
        return row;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? LocalDateTime.now() : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
