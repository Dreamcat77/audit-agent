package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * local profile 的 F02 规则配置内存适配器。
 *
 * <p>适配器为五条确定性规则提供固定版本配置，仅用于本地开发和测试；正式配置仍由 DM8 持久化。</p>
 */
@Repository
@Profile("local")
public class InMemoryContractRuleConfigRepository implements ContractRuleConfigRepository {

    private final List<ContractRuleConfiguration> configurations = F02RuleCodes.ALL.stream()
            .map(code -> new ContractRuleConfiguration(
                    code, code, true, RiskLevel.RED, true, 1,
                    "Deterministic F02 local rule", Instant.parse("2026-08-17T00:00:00Z")))
            .toList();

    /**
     * 返回 local 环境固定启用的五条 F02 规则配置。
     *
     * @return local 规则配置列表
     */
    @Override
    public List<ContractRuleConfiguration> findEnabledForF02() {
        return configurations;
    }
}
