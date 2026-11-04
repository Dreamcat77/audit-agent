package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractRuleConfigRepository implements ContractRuleConfigRepository {

    private final ContractRuleConfigMapper mapper;

    public Dm8ContractRuleConfigRepository(ContractRuleConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractRuleConfiguration> findEnabledForF02() {
        return mapper.findEnabledF02().stream()
                .map(row -> new ContractRuleConfiguration(
                        row.ruleCode(), row.ruleName(), row.enabled() == 1,
                        RiskLevel.valueOf(row.failLevel()), row.blockSign() == 1,
                        row.versionNo(), row.description(), row.updatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
    }
}
