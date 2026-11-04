package com.fuquan.audit.review.infra.memory;

import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * local profile 的只追加检查结果内存适配器。
 *
 * <p>该实现保持同一任务只能首次写入一批结果的约束，用于本地测试；正式检查事实由 DM8 持久化。</p>
 */
@Repository
@Profile("local")
public class InMemoryAuditCheckResultRepository implements AuditCheckResultRepository {

    private final ConcurrentMap<UUID, List<AuditCheckResult>> resultsByTask = new ConcurrentHashMap<>();

    /**
     * 为一个任务只追加一次不可变检查结果快照。
     *
     * @param results 必须非空且属于同一任务的结果批次
     * @throws NullPointerException 结果列表为空引用时抛出
     * @throws IllegalArgumentException 列表为空或包含多个任务结果时抛出
     * @throws IllegalStateException 该任务已经写入结果时抛出
     */
    @Override
    public void appendAll(List<AuditCheckResult> results) {
        List<AuditCheckResult> snapshot = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty");
        }
        UUID taskId = snapshot.get(0).taskId();
        if (snapshot.stream().anyMatch(result -> !result.taskId().equals(taskId))) {
            throw new IllegalArgumentException("all results must belong to one task");
        }
        if (resultsByTask.putIfAbsent(taskId, snapshot) != null) {
            throw new IllegalStateException("audit check results are append-only");
        }
    }

    /**
     * 查询 local 内存中指定任务的结果快照。
     *
     * @param taskId 审查任务标识
     * @return 已追加结果；不存在时返回空列表
     */
    @Override
    public List<AuditCheckResult> findByTaskId(UUID taskId) {
        return resultsByTask.getOrDefault(taskId, List.of());
    }

    @Override
    public Optional<AuditCheckResult> findById(UUID id) {
        return resultsByTask.values().stream()
                .flatMap(List::stream)
                .filter(result -> result.id().equals(id))
                .findFirst();
    }
}
