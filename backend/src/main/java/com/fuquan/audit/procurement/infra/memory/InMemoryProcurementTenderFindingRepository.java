package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementTenderFindingRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementTenderFindingRepository implements ProcurementTenderFindingRepository {
    private final ConcurrentMap<UUID, List<ProcurementTenderFinding>> findings = new ConcurrentHashMap<>();

    @Override
    public void replaceForReview(UUID projectId, UUID reviewId, List<ProcurementTenderFinding> values) {
        findings.put(reviewId, List.copyOf(values));
    }

    @Override
    public List<ProcurementTenderFinding> findByReviewId(UUID projectId, UUID reviewId) {
        return List.copyOf(findings.getOrDefault(reviewId, List.of()));
    }
}
