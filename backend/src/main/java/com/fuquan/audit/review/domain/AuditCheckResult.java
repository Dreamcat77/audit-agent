package com.fuquan.audit.review.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条规则为一个审查任务产生的只追加正式检查事实。
 *
 * @param id 结果唯一标识
 * @param taskId 所属审查任务标识
 * @param ruleCode 稳定规则编码
 * @param itemKey 关联业务明细键，可为空
 * @param checkStatus 检查状态
 * @param riskLevel 风险等级
 * @param blockSign 是否阻断后续签署
 * @param actualValue 实际值，可为空
 * @param expectedValue 期望值，可为空
 * @param detail 结论说明
 * @param ruleVersion 执行时采用的规则版本
 * @param createdAt 结论形成时间
 */
public record AuditCheckResult(
        UUID id,
        UUID taskId,
        String ruleCode,
        String itemKey,
        CheckStatus checkStatus,
        RiskLevel riskLevel,
        boolean blockSign,
        String actualValue,
        String expectedValue,
        String detail,
        long ruleVersion,
        Instant createdAt) {

    /**
     * 校验检查结果的身份、状态、风险和时间字段完整性。
     *
     * @throws NullPointerException 必填字段为空时抛出
     */
    public AuditCheckResult {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(checkStatus, "checkStatus must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
