package com.fuquan.audit.contract.application;

import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.RiskLevel;
import java.util.List;
import java.util.Objects;

/**
 * 汇总一次已持久化 F02 任务及其全部规则结果。
 *
 * @param task 审查任务
 * @param findings 已持久化检查结论
 * @param overallRisk 全部结论聚合后的最高风险
 * @param blockSign 是否存在阻断签署的结论
 * @param replayed 是否由相同幂等请求重放历史结果
 */
public record ContractReviewResult(
        AuditTask task,
        List<AuditCheckResult> findings,
        RiskLevel overallRisk,
        boolean blockSign,
        boolean replayed) {

    /**
     * 校验任务和风险汇总完整性，并建立结论列表不可变快照。
     *
     * @throws NullPointerException 任务、结论列表或总体风险为空时抛出
     */
    public ContractReviewResult {
        Objects.requireNonNull(task, "task must not be null");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        Objects.requireNonNull(overallRisk, "overallRisk must not be null");
    }
}
