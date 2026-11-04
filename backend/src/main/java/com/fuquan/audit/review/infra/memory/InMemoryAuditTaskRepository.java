package com.fuquan.audit.review.infra.memory;

import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.application.port.AuditTaskReadQueryPort;
import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * local profile 的并发安全审查任务内存适配器。
 *
 * <p>实现以原子 Map 操作模拟正式 DM8 适配器的唯一键和状态/版本乐观锁约束。</p>
 */
@Repository
@Profile("local")
public class InMemoryAuditTaskRepository implements AuditTaskRepository, AuditTaskReadQueryPort {

    private final ConcurrentMap<UUID, AuditTask> tasks = new ConcurrentHashMap<>();

    /**
     * 原子创建 local 审查任务。
     *
     * @param task 待创建任务
     * @return 创建成功的任务
     * @throws NullPointerException 任务为空时抛出
     * @throws AuditTaskConcurrencyException 相同标识任务已经存在时抛出
     */
    @Override
    public AuditTask create(AuditTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new AuditTaskConcurrencyException("audit task already exists");
        }
        return task;
    }

    /**
     * 按标识读取 local 任务快照。
     *
     * @param id 任务唯一标识
     * @return 找到的任务；不存在时返回空
     */
    @Override
    public Optional<AuditTask> findById(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /**
     * 查询指定业务来源和审查类型最近创建的 local 任务。
     *
     * @param sourceType 业务来源类型
     * @param sourceId 业务来源标识
     * @param reviewType 审查类型
     * @return 按创建时间和任务标识确定的最近任务
     */
    @Override
    public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
        return tasks.values().stream()
                .filter(task -> task.sourceType().equals(sourceType)
                        && task.sourceId().equals(sourceId)
                        && task.reviewType().equals(reviewType))
                .max(Comparator.comparing(AuditTask::createdAt).thenComparing(AuditTask::id));
    }

    @Override
    public Page findLatest(String sourceType, String reviewType, int page, int size) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        if (page < 1 || size < 1) throw new IllegalArgumentException("invalid audit task page");
        Comparator<AuditTask> newest = Comparator.comparing(AuditTask::createdAt)
                .thenComparing(AuditTask::id);
        java.util.Map<UUID, AuditTask> latestBySource = new java.util.HashMap<>();
        tasks.values().stream()
                .filter(task -> task.sourceType().equals(sourceType) && task.reviewType().equals(reviewType))
                .forEach(task -> latestBySource.merge(task.sourceId(), task,
                        (left, right) -> newest.compare(left, right) >= 0 ? left : right));
        java.util.List<AuditTask> ordered = latestBySource.values().stream()
                .sorted(newest.reversed())
                .toList();
        int offset = Math.min((page - 1) * size, ordered.size());
        int end = Math.min(offset + size, ordered.size());
        return new Page(ordered.subList(offset, end), page, size, ordered.size());
    }

    @Override
    public java.util.List<AuditTask> findAllLatest(String sourceType, String reviewType) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        Comparator<AuditTask> newest = Comparator.comparing(AuditTask::createdAt)
                .thenComparing(AuditTask::id);
        java.util.Map<UUID, AuditTask> latestBySource = new java.util.HashMap<>();
        tasks.values().stream()
                .filter(task -> task.sourceType().equals(sourceType) && task.reviewType().equals(reviewType))
                .forEach(task -> latestBySource.merge(task.sourceId(), task,
                        (left, right) -> newest.compare(left, right) >= 0 ? left : right));
        return latestBySource.values().stream().sorted(newest.reversed()).toList();
    }

    @Override
    public Page findAllPage(String sourceType, String reviewType, int page, int size) {
        if (page < 1 || size < 1) throw new IllegalArgumentException("invalid audit task page");
        java.util.List<AuditTask> ordered = findAll(sourceType, reviewType);
        int offset = Math.min((page - 1) * size, ordered.size());
        int end = Math.min(offset + size, ordered.size());
        return new Page(ordered.subList(offset, end), page, size, ordered.size());
    }

    @Override
    public java.util.List<AuditTask> findAll(String sourceType, String reviewType) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(reviewType, "reviewType must not be null");
        return tasks.values().stream().filter(task -> task.sourceType().equals(sourceType)
                        && task.reviewType().equals(reviewType))
                .sorted(Comparator.comparing(AuditTask::createdAt).thenComparing(AuditTask::id).reversed())
                .toList();
    }

    /**
     * 原子校验期望状态和版本后执行任务状态转换。
     *
     * @param id 任务唯一标识
     * @param expectedStatus 期望当前状态
     * @param expectedVersion 期望当前版本
     * @param targetStatus 目标状态
     * @param occurredAt 转换业务时间
     * @return 转换后的任务快照
     * @throws NullPointerException 任务标识为空时抛出
     * @throws AuditTaskConcurrencyException 任务不存在、状态版本冲突或转换非法时抛出
     */
    @Override
    public AuditTask transition(
            UUID id,
            AuditTaskStatus expectedStatus,
            long expectedVersion,
            AuditTaskStatus targetStatus,
            Instant occurredAt) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            return tasks.compute(id, (taskId, current) -> {
                if (current == null
                        || current.status() != expectedStatus
                        || current.versionNo() != expectedVersion) {
                    throw new AuditTaskConcurrencyException("audit task state or version conflict");
                }
                try {
                    return current.transitionTo(targetStatus, occurredAt);
                } catch (IllegalArgumentException exception) {
                    throw new AuditTaskConcurrencyException(exception.getMessage());
                }
            });
        } catch (AuditTaskConcurrencyException exception) {
            throw exception;
        }
    }
}
