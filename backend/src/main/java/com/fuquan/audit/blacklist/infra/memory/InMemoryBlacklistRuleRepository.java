package com.fuquan.audit.blacklist.infra.memory;

import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBlacklistRuleRepository implements BlacklistRuleRepository {

    private final List<BlacklistRule> stored = new ArrayList<>();

    @Override
    public List<BlacklistRule> findAllActive() {
        return stored.stream().filter(BlacklistRule::active).toList();
    }

    @Override
    public List<BlacklistRule> findAll() {
        return Collections.unmodifiableList(stored);
    }

    @Override
    public Optional<BlacklistRule> findById(UUID id) {
        return stored.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    @Override
    public BlacklistRule save(BlacklistRule rule) {
        stored.removeIf(item -> item.id().equals(rule.id()));
        stored.add(rule);
        return rule;
    }

    @Override
    public BlacklistRule update(BlacklistRule rule) {
        return save(rule);
    }

    @Override
    public void deleteById(UUID id) {
        stored.removeIf(item -> item.id().equals(id));
    }
}
