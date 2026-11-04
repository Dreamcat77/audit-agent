package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.TenderRuleCatalog;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementTenderRuleRepository implements ProcurementTenderRuleRepository {
    private final List<ProcurementTenderRule> rules =
            List.copyOf(TenderRuleCatalog.defaults(Clock.systemUTC().instant()));

    @Override
    public List<ProcurementTenderRule> findAll() { return rules; }

    @Override
    public List<ProcurementTenderRule> findEnabled() {
        return rules.stream().filter(ProcurementTenderRule::enabled).toList();
    }
}
