package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementSourceProjectPort;
import com.fuquan.audit.procurement.domain.ProcurementSourceProject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementSourceProjectAdapter implements ProcurementSourceProjectPort {

    private final Dm8MaterialIntakeRepository materials;
    private final MaterialIntakeMapper mapper;

    public Dm8ProcurementSourceProjectAdapter(Dm8MaterialIntakeRepository materials, MaterialIntakeMapper mapper) {
        this.materials = materials;
        this.mapper = mapper;
    }

    @Override
    public List<ProcurementSourceProject> findEligible(String actorId, String keyword, int limit) {
        return mapper.eligibleForProcurement(actorId, keyword, limit).stream().map(materials::domain)
                .map(ProcurementSourceProject::from).toList();
    }

    @Override
    public Optional<ProcurementSourceProject> findEligibleById(String actorId, UUID projectId) {
        return materials.find(projectId).filter(m -> m.actorId().equals(actorId) && "PARSED".equals(m.status()))
                .map(ProcurementSourceProject::from);
    }

}
