package com.fuquan.audit.review.application.port;

import com.fuquan.audit.review.domain.AuditCheckResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 公共正式规则检查事实的只追加 Repository Port。
 *
 * <p>DM8 是正式检查结果的事实源；端口禁止以缓存替代持久化结果，并供 contract 等审查模块复用。</p>
 */
public interface AuditCheckResultRepository {

    /**
     * 原子追加同一任务的一批正式检查结果。
     *
     * @param results 同一审查任务产生且不可覆盖的检查结果
     */
    void appendAll(List<AuditCheckResult> results);

    /**
     * 查询指定任务已经持久化的全部检查结果。
     *
     * @param taskId 审查任务标识
     * @return 任务检查结果列表，不存在时返回空列表
     */
    List<AuditCheckResult> findByTaskId(UUID taskId);

    /** Finds one durable result by its semantic identifier. */
    Optional<AuditCheckResult> findById(UUID id);
}
