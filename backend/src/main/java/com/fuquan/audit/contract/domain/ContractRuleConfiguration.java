package com.fuquan.audit.contract.domain;

import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示一条 F02 规则的可持久化执行配置和版本信息。
 *
 * @param ruleCode 稳定规则编码
 * @param ruleName 规则名称
 * @param enabled 是否参与新审查
 * @param failLevel 规则失败时的风险等级
 * @param blockSign 规则失败时是否阻断签署
 * @param versionNo 规则版本号
 * @param description 配置说明，可为空
 * @param updatedAt 配置更新时间
 */
public record ContractRuleConfiguration(
        String ruleCode,
        String ruleName,
        boolean enabled,
        RiskLevel failLevel,
        boolean blockSign,
        long versionNo,
        String description,
        Instant updatedAt) {

    /**
     * 校验规则身份、失败策略、版本和更新时间的完整性。
     *
     * @throws NullPointerException 必填字段为 {@code null} 时抛出
     * @throws IllegalArgumentException 必填文本为空白或版本号为负数时抛出
     */
    public ContractRuleConfiguration {
        ruleCode = requireNotBlank(ruleCode, "ruleCode");
        ruleName = requireNotBlank(ruleName, "ruleName");
        Objects.requireNonNull(failLevel, "failLevel must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * 校验规则配置中的必填文本并原样返回。
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
