package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewSnapshotRepository;
import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 的智能体预审最新快照存储。 */
@Repository
@Profile("local")
public class InMemoryProcurementAgentReviewSnapshotRepository
        implements ProcurementAgentReviewSnapshotRepository {

    private final ConcurrentMap<UUID, ProcurementAgentReviewSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(ProcurementAgentReviewSnapshot snapshot) {
        snapshots.put(snapshot.projectId(), snapshot);
    }

    @Override
    public Optional<ProcurementAgentReviewSnapshot> findByProjectId(UUID projectId) {
        return Optional.ofNullable(snapshots.get(projectId));
    }

    @Override
    public Map<UUID, ProcurementAgentReviewSnapshot> findByProjectIds(Collection<UUID> projectIds) {
        return projectIds.stream()
                .map(snapshots::get)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                        ProcurementAgentReviewSnapshot::projectId, snapshot -> snapshot));
    }

    @Override
    public void deleteByProjectId(UUID projectId) {
        snapshots.remove(projectId);
    }
}
