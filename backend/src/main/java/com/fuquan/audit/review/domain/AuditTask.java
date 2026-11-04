package com.fuquan.audit.review.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 公共审查任务聚合，记录业务来源快照、状态、追踪字段和乐观锁版本。
 *
 * @param id 任务唯一标识
 * @param sourceType 业务来源类型
 * @param sourceId 业务来源标识
 * @param sourceVersion 审查时业务来源版本
 * @param reviewType 审查类型
 * @param status 当前任务状态
 * @param createdBy 创建用户标识
 * @param requestId 创建请求标识
 * @param traceId 创建调用链标识
 * @param createdAt 创建时间
 * @param startedAt 开始处理时间，可为空
 * @param finishedAt 完成或失败时间，可为空
 * @param versionNo 乐观锁版本号
 */
public record AuditTask(
        UUID id,
        String sourceType,
        UUID sourceId,
        long sourceVersion,
        String reviewType,
        AuditTaskStatus status,
        String createdBy,
        String requestId,
        String traceId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        long versionNo) {

    /**
     * 校验任务身份、来源版本、状态、追踪字段和乐观锁版本。
     *
     * @throws NullPointerException 必填字段为空时抛出
     * @throws IllegalArgumentException 必填文本为空白或版本号为负数时抛出
     */
    public AuditTask {
        Objects.requireNonNull(id, "id must not be null");
        sourceType = requireNotBlank(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must be non-negative");
        }
        reviewType = requireNotBlank(reviewType, "reviewType");
        Objects.requireNonNull(status, "status must not be null");
        createdBy = requireNotBlank(createdBy, "createdBy");
        requestId = requireNotBlank(requestId, "requestId");
        traceId = requireNotBlank(traceId, "traceId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
    }

    /**
     * 创建尚未开始执行的 PENDING 审查任务。
     *
     * @param id 任务唯一标识
     * @param sourceType 业务来源类型
     * @param sourceId 业务来源标识
     * @param sourceVersion 审查时业务来源版本
     * @param reviewType 审查类型
     * @param createdBy 创建用户标识
     * @param requestId 创建请求标识
     * @param traceId 创建调用链标识
     * @param createdAt 创建时间
     * @return 版本为 0 且未设置开始、结束时间的 PENDING 任务
     */
    public static AuditTask pending(
            UUID id,
            String sourceType,
            UUID sourceId,
            long sourceVersion,
            String reviewType,
            String createdBy,
            String requestId,
            String traceId,
            Instant createdAt) {
        return new AuditTask(
                id, sourceType, sourceId, sourceVersion, reviewType, AuditTaskStatus.PENDING,
                createdBy, requestId, traceId, createdAt, null, null, 0);
    }

    /**
     * 按状态机规则生成下一状态的不可变任务快照。
     *
     * <p>进入 PROCESSING 时记录开始时间，进入 DONE 或 FAILED 时记录结束时间，并将乐观锁版本递增。</p>
     *
     * @param target 目标任务状态
     * @param occurredAt 状态转换业务时间
     * @return 转换后的新任务快照
     * @throws NullPointerException 目标状态或发生时间为空时抛出
     * @throws IllegalArgumentException 当前状态不允许转换到目标状态时抛出
     */
    public AuditTask transitionTo(AuditTaskStatus target, Instant occurredAt) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalArgumentException("illegal audit task transition: " + status + " -> " + target);
        }
        return new AuditTask(
                id, sourceType, sourceId, sourceVersion, reviewType, target, createdBy,
                requestId, traceId, createdAt,
                target == AuditTaskStatus.PROCESSING ? occurredAt : startedAt,
                target == AuditTaskStatus.DONE || target == AuditTaskStatus.FAILED ? occurredAt : finishedAt,
                versionNo + 1);
    }

    /**
     * 校验任务领域中的必填文本并原样返回。
     *
     * @param value 待校验文本
     * @param field 用于异常信息的字段名
     * @return 通过非空白校验的原文本
     * @throws NullPointerException 文本为 {@code null} 时抛出
     * @throws IllegalArgumentException 文本为空白时抛出
     */
    private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
