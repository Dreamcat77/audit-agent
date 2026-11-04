package com.fuquan.audit.contract.domain;

import java.util.List;

/**
 * F02 单条确定性规则的领域扩展点。
 *
 * <p>实现必须只基于结构化审查上下文和已持久化规则配置产生可重复结果，不以 AI 或 OCR 推理替代金额及主体一致性判断。</p>
 */
public interface ContractReviewRule {

    /**
     * 返回实现所对应的稳定规则编码。
     *
     * @return 与规则配置表关联的 F02 规则编码
     */
    String ruleCode();

    /**
     * 使用指定配置评价合同，并返回该规则产生的全部结论。
     *
     * @param context 本次审查的结构化合同上下文
     * @param configuration 本次执行锁定的规则配置及版本
     * @return 不可由调用方假定为空的规则结论列表
     */
    List<ContractReviewFinding> evaluate(
            ContractReviewContext context,
            ContractRuleConfiguration configuration);
}
