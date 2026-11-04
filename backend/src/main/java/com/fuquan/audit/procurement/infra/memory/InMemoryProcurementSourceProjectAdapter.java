package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementSourceProjectPort;
import com.fuquan.audit.procurement.domain.ProcurementSourceProject;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementSourceProjectAdapter implements ProcurementSourceProjectPort {
    private final MaterialIntakeRepository materials;
    private final InMemoryProcurementProjectQueryAdapter projects;
    public InMemoryProcurementSourceProjectAdapter(MaterialIntakeRepository materials,
            InMemoryProcurementProjectQueryAdapter projects) {
        this.materials = materials; this.projects = projects;
    }

    @Override
    public List<ProcurementSourceProject> findEligible(String actorId, String keyword, int limit) {
        var used = projects.usedMaterialIds();
        return materials.eligible(keyword, Integer.MAX_VALUE).stream()
                .filter(m -> m.actorId().equals(actorId) && !used.contains(m.id()))
                .limit(limit).map(ProcurementSourceProject::from).toList();
    }

    @Override
    public Optional<ProcurementSourceProject> findEligibleById(String actorId, UUID projectId) {
        return materials.find(projectId).filter(m -> m.actorId().equals(actorId) && "PARSED".equals(m.status()))
                .map(ProcurementSourceProject::from);
    }
}
