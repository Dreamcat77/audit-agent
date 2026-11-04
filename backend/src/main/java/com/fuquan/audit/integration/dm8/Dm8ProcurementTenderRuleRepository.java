package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementTenderRuleRepository implements ProcurementTenderRuleRepository {
    private final ProcurementTenderRuleMapper mapper;

    public Dm8ProcurementTenderRuleRepository(ProcurementTenderRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementTenderRule> findAll() {
        return mapper.findAll().stream().map(Dm8ProcurementTenderRuleRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementTenderRule> findEnabled() {
        return mapper.findEnabled().stream().map(Dm8ProcurementTenderRuleRepository::toDomain).toList();
    }

    private static ProcurementTenderRule toDomain(ProcurementTenderRulePersistenceRow row) {
        return new ProcurementTenderRule(java.util.UUID.fromString(row.getId()), row.getRuleCode(),
                row.getRuleName(), row.getRiskCategory(), row.getMatchType(), row.getMatchContent(),
                row.getRiskLevel(), row.getRemediationSuggestion(), row.getLegalBasis(),
                row.getRuleExplanation(), row.getSource(),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getCreatedTime().toInstant(ZoneOffset.UTC), row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
