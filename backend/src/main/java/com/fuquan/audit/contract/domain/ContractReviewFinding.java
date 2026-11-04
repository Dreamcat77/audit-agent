package com.fuquan.audit.contract.domain;

import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.util.Objects;

/**
 * 表示一条 F02 确定性规则结论及其可展示证据。
 *
 * @param ruleCode 产生结论的稳定规则编码
 * @param itemKey 关联的合同明细业务键，合同级结论可为空
 * @param checkStatus 检查状态
 * @param riskLevel 风险等级
 * @param blockSign 是否阻断合同签署
 * @param actualValue 实际值，可为空
 * @param expectedValue 期望值，可为空
 * @param detail 面向审查人员的结论说明
 * @param ruleVersion 执行时采用的规则版本
 */
public record ContractReviewFinding(
        String ruleCode,
        String itemKey,
        CheckStatus checkStatus,
        RiskLevel riskLevel,
        boolean blockSign,
        String actualValue,
        String expectedValue,
        String detail,
        long ruleVersion) {

    /**
     * 校验规则结论的一致性，特别保证 PASS 只能是 GREEN 且不得阻断签署。
     *
     * @throws NullPointerException 规则编码、检查状态、风险等级或说明为空时抛出
     * @throws IllegalArgumentException 版本号为负数或 PASS 结论违反风险约束时抛出
     */
    public ContractReviewFinding {
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(checkStatus, "checkStatus must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if (ruleVersion < 0) {
            throw new IllegalArgumentException("ruleVersion must be non-negative");
        }
        if (checkStatus == CheckStatus.PASS && (riskLevel != RiskLevel.GREEN || blockSign)) {
            throw new IllegalArgumentException("PASS must be GREEN and non-blocking");
        }
    }

    /**
     * 根据生效规则配置创建通过结论。
     *
     * @param config 本次执行使用的规则配置
     * @param itemKey 关联明细键，合同级规则可为空
     * @param actual 实际值
     * @param expected 期望值
     * @param detail 通过原因
     * @return GREEN、非阻断的 PASS 结论
     */
    public static ContractReviewFinding pass(
            ContractRuleConfiguration config, String itemKey, String actual, String expected, String detail) {
        return new ContractReviewFinding(
                config.ruleCode(), itemKey, CheckStatus.PASS, RiskLevel.GREEN, false,
                actual, expected, detail, config.versionNo());
    }

    /**
     * 根据生效规则配置创建失败结论。
     *
     * @param config 本次执行使用的规则配置
     * @param itemKey 关联明细键，合同级规则可为空
     * @param actual 实际值
     * @param expected 期望值
     * @param detail 失败原因
     * @return 使用配置风险等级和阻断策略的 FAIL 结论
     */
    public static ContractReviewFinding fail(
            ContractRuleConfiguration config, String itemKey, String actual, String expected, String detail) {
        return new ContractReviewFinding(
                config.ruleCode(), itemKey, CheckStatus.FAIL, config.failLevel(), config.blockSign(),
                actual, expected, detail, config.versionNo());
    }

    /**
     * 为依据缺失或输入不可解析的场景创建未评价结论。
     *
     * @param config 本次执行使用的规则配置
     * @param itemKey 关联明细键，合同级规则可为空
     * @param detail 无法评价的原因
     * @return YELLOW 且阻断签署的 NOT_EVALUATED 结论
     */
    public static ContractReviewFinding notEvaluated(
            ContractRuleConfiguration config, String itemKey, String detail) {
        return new ContractReviewFinding(
                config.ruleCode(), itemKey, CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW, true,
                null, null, detail, config.versionNo());
    }
}
