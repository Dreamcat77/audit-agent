package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlacklistRuleRepository {

    List<BlacklistRule> findAllActive();

    List<BlacklistRule> findAll();

    Optional<BlacklistRule> findById(UUID id);

    BlacklistRule save(BlacklistRule rule);

    BlacklistRule update(BlacklistRule rule);

    void deleteById(UUID id);
}
