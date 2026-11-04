package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 智能体预审最新快照持久化 Port。 */
public interface ProcurementAgentReviewSnapshotRepository {

    void save(ProcurementAgentReviewSnapshot snapshot);

    Optional<ProcurementAgentReviewSnapshot> findByProjectId(UUID projectId);

    /** 按项目 ID 批量读取最新快照，避免列表页逐项目查询。 */
    default Map<UUID, ProcurementAgentReviewSnapshot> findByProjectIds(Collection<UUID> projectIds) {
        return projectIds.stream()
                .map(this::findByProjectId)
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toMap(
                        ProcurementAgentReviewSnapshot::projectId, snapshot -> snapshot));
    }

    void deleteByProjectId(UUID projectId);
}
