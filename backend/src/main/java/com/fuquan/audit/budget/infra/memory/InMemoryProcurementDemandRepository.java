package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.ProcurementDemandRepository;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementDemandRepository implements ProcurementDemandRepository {

    private final Map<UUID, ProcurementDemand> demands = LocalBudgetFixtures.procurementDemands().stream()
            .collect(Collectors.toUnmodifiableMap(ProcurementDemand::id, Function.identity()));

    @Override
    public Optional<ProcurementDemand> findById(UUID id) {
        return Optional.ofNullable(demands.get(id));
    }

    @Override
    public java.util.List<ProcurementDemand> findAllByIds(java.util.List<UUID> ids) {
        return ids.stream().map(demands::get).filter(java.util.Objects::nonNull).toList();
    }
}
