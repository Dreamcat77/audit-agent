package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewSnapshotRepository;
import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** DM8 智能体预审最新快照持久化适配器。 */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementAgentReviewSnapshotRepository
        implements ProcurementAgentReviewSnapshotRepository {

    private final ProcurementAgentReviewSnapshotMapper mapper;

    public Dm8ProcurementAgentReviewSnapshotRepository(ProcurementAgentReviewSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(ProcurementAgentReviewSnapshot snapshot) {
        ProcurementAgentReviewSnapshotPersistenceRow row = new ProcurementAgentReviewSnapshotPersistenceRow();
        row.setId(snapshot.id().toString());
        row.setProjectId(snapshot.projectId().toString());
        row.setGeneratedTime(LocalDateTime.ofInstant(snapshot.generatedTime(), ZoneOffset.UTC));
        row.setReviewJson(snapshot.reviewJson());
        if (mapper.update(row) == 0) {
            mapper.insert(row);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementAgentReviewSnapshot> findByProjectId(UUID projectId) {
        ProcurementAgentReviewSnapshotPersistenceRow row = mapper.findByProjectId(projectId.toString());
        if (row == null) return Optional.empty();
        return Optional.of(new ProcurementAgentReviewSnapshot(
                UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()),
                row.getGeneratedTime().toInstant(ZoneOffset.UTC), row.getReviewJson()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ProcurementAgentReviewSnapshot> findByProjectIds(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return Map.of();
        List<String> ids = projectIds.stream().map(UUID::toString).toList();
        return mapper.findByProjectIds(ids).stream()
                .map(row -> new ProcurementAgentReviewSnapshot(
                        UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()),
                        row.getGeneratedTime().toInstant(ZoneOffset.UTC), row.getReviewJson()))
                .collect(Collectors.toMap(ProcurementAgentReviewSnapshot::projectId, Function.identity()));
    }

    @Override
    @Transactional
    public void deleteByProjectId(UUID projectId) {
        mapper.deleteByProjectId(projectId.toString());
    }
}
