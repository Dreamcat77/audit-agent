package com.fuquan.audit.review.application.port;

import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 公共审查任务聚合的 Repository Port。
 *
 * <p>正式适配器将任务和状态版本持久化到 DM8；状态转换必须同时校验期望状态与期望版本。</p>
 */
public interface AuditTaskRepository {

    /**
     * 创建新的审查任务事实。
     *
     * @param task 初始审查任务
     * @return 已持久化任务
     * @throws AuditTaskConcurrencyException 相同任务标识已存在时抛出
     */
    AuditTask create(AuditTask task);

    /**
     * 按标识查询审查任务。
     *
     * @param id 任务唯一标识
     * @return 找到的任务；不存在时返回空
     */
    Optional<AuditTask> findById(UUID id);

    /**
     * 查询指定业务来源和审查类型最近创建的任务。
     *
     * @param sourceType 业务来源类型
     * @param sourceId 业务来源标识
     * @param reviewType 审查类型
     * @return 最近任务；不存在时返回空
     */
    Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType);

    /**
     * 使用状态和版本双重条件执行乐观锁状态转换。
     *
     * @param id 任务唯一标识
     * @param expectedStatus 调用方预期的当前状态
     * @param expectedVersion 调用方预期的当前版本
     * @param targetStatus 目标状态
     * @param occurredAt 状态转换业务时间
     * @return 转换后的新任务快照
     * @throws AuditTaskConcurrencyException 任务不存在、状态或版本冲突、转换非法时抛出
     */
    AuditTask transition(
            UUID id,
            AuditTaskStatus expectedStatus,
            long expectedVersion,
            AuditTaskStatus targetStatus,
            Instant occurredAt);
}
