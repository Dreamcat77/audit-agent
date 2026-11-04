package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
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
public class Dm8BlacklistRuleRepository implements BlacklistRuleRepository {

    private final BlacklistRuleMapper mapper;

    public Dm8BlacklistRuleRepository(BlacklistRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BlacklistRule> findAllActive() {
        return mapper.findAllActive().stream().map(Dm8BlacklistRuleRepository::toDomain).toList();
    }

    @Override
    public List<BlacklistRule> findAll() {
        return mapper.findAll().stream().map(Dm8BlacklistRuleRepository::toDomain).toList();
    }

    @Override
    public Optional<BlacklistRule> findById(UUID id) {
        BlacklistRulePersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    @Transactional
    public BlacklistRule save(BlacklistRule rule) {
        mapper.insert(toRow(rule));
        return rule;
    }

    @Override
    @Transactional
    public BlacklistRule update(BlacklistRule rule) {
        mapper.update(toRow(rule));
        return rule;
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        mapper.deleteById(id.toString());
    }

    private static BlacklistRule toDomain(BlacklistRulePersistenceRow row) {
        return new BlacklistRule(
                UUID.fromString(row.getId()), row.getRuleName(), row.getRuleType(),
                row.getBlacklistTypes(), row.getAlertLevel(),
                Boolean.TRUE.equals(row.getAutoResolve()), Boolean.TRUE.equals(row.getActive()),
                toInstant(row.getCreatedTime()), toInstant(row.getUpdatedTime()));
    }

    private static BlacklistRulePersistenceRow toRow(BlacklistRule domain) {
        BlacklistRulePersistenceRow row = new BlacklistRulePersistenceRow();
        row.setId(domain.id().toString());
        row.setRuleName(domain.ruleName());
        row.setRuleType(domain.ruleType());
        row.setBlacklistTypes(domain.blacklistTypes());
        row.setAlertLevel(domain.alertLevel());
        row.setAutoResolve(domain.autoResolve());
        row.setActive(domain.active());
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
