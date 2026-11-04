package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03 黑名单规则配置写入用例。
 */
@Service
public class BlacklistRuleManagementService {

    private final BlacklistRuleRepository ruleRepository;

    public BlacklistRuleManagementService(BlacklistRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional
    public BlacklistRule add(RuleCommand command) {
        Instant now = Instant.now();
        return ruleRepository.save(new BlacklistRule(
                UUID.randomUUID(), command.ruleName(), command.ruleType(),
                command.blacklistTypes(), command.alertLevel(), command.autoResolve(),
                true, now, now));
    }

    @Transactional
    public BlacklistRule update(UUID id, RuleCommand command) {
        BlacklistRule old = ruleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "blacklist rule not found: " + id));
        return ruleRepository.update(new BlacklistRule(
                id, command.ruleName(), command.ruleType(), command.blacklistTypes(),
                command.alertLevel(), command.autoResolve(), old.active(),
                old.createdTime(), Instant.now()));
    }

    @Transactional
    public void delete(UUID id) {
        ruleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "blacklist rule not found: " + id));
        ruleRepository.deleteById(id);
    }

    public record RuleCommand(
            String ruleName,
            String ruleType,
            String blacklistTypes,
            String alertLevel,
            boolean autoResolve) {
    }
}
