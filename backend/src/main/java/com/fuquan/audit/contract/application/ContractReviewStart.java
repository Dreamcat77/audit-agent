package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.review.domain.AuditTask;

/**
 * 表示持久化 START 短事务的结果：新执行身份或已完成结果重放。
 *
 * @param task 新执行已提交的 PROCESSING 任务；重放时为空
 * @param idempotency 新执行已提交的幂等记录；重放时为空
 * @param replay 历史成功结果；新执行时为空
 */
public record ContractReviewStart(
        AuditTask task,
        IdempotencyRecord idempotency,
        ContractReviewResult replay) {

    /**
     * 创建已经成功提交 START 事实的新执行结果。
     *
     * @param task 已进入 PROCESSING 的任务
     * @param idempotency 已取得的持久化幂等记录
     * @return 新执行 START 结果
     */
    public static ContractReviewStart executing(AuditTask task, IdempotencyRecord idempotency) {
        return new ContractReviewStart(task, idempotency, null);
    }

    /**
     * 创建无需再次执行规则的幂等历史结果重放。
     *
     * @param result 已持久化的历史成功结果
     * @return 重放 START 结果
     */
    public static ContractReviewStart replay(ContractReviewResult result) {
        return new ContractReviewStart(null, null, result);
    }

    /**
     * 判断调用方是否应直接返回历史结果。
     *
     * @return 存在历史重放结果时为 {@code true}
     */
    public boolean isReplay() {
        return replay != null;
    }
}
