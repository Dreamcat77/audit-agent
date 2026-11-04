package com.fuquan.audit.review.domain;

/**
 * 审查任务持久化状态。
 *
 * <p>新同步审查只允许 PENDING → PROCESSING → DONE/FAILED；其余值仅用于读取既有 DM8 数据。</p>
 */
public enum AuditTaskStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED,
    /** 为保证既有 DM8 行可反序列化而保留的只读历史状态。 */
    PARSING,
    REVIEWING,
    NEEDS_MANUAL_REVIEW;

    /**
     * 判断当前状态是否允许转换到目标状态。
     *
     * @param target 目标状态
     * @return 符合同步审查状态机时为 {@code true}
     */
    public boolean canTransitionTo(AuditTaskStatus target) {
        return this == PENDING && target == PROCESSING
                || this == PROCESSING && (target == DONE || target == FAILED);
    }
}
