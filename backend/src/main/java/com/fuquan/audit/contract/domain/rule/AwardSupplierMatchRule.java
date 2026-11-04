package com.fuquan.audit.contract.domain.rule;

import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * F02 合同乙方与中标供应商一致性规则。
 *
 * <p>双方信用代码均存在时优先按信用代码比较，否则回退到规范化供应商名称；身份依据不完整时返回 NOT_EVALUATED。</p>
 */
public final class AwardSupplierMatchRule implements ContractReviewRule {

    /**
     * 返回合同供应商与中标供应商一致性规则编码。
     *
     * @return {@code F02_AWARD_SUPPLIER_MATCH}
     */
    @Override
    public String ruleCode() {
        return F02RuleCodes.AWARD_SUPPLIER_MATCH;
    }

    /**
     * 按信用代码优先、名称兜底的顺序比较合同乙方与中标供应商。
     *
     * @param context 包含合同主体与中标依据的审查上下文
     * @param configuration 本次执行的风险、阻断和版本配置
     * @return 单条 PASS、FAIL 或 NOT_EVALUATED 结论
     */
    @Override
    public List<ContractReviewFinding> evaluate(
            ContractReviewContext context,
            ContractRuleConfiguration configuration) {
        AwardNoticeReference award = context.contract().awardNotice();
        if (award == null) {
            return List.of(ContractReviewFinding.notEvaluated(
                    configuration, null, "Award notice supplier basis is missing"));
        }
        String contractCode = normalizeCode(context.contract().partyBCreditCode());
        String awardCode = normalizeCode(award.winningBidderCreditCode());
        boolean match;
        String basis;
        if (contractCode != null && awardCode != null) {
            match = contractCode.equals(awardCode);
            basis = "credit code";
        } else {
            String contractName = normalizeName(context.contract().partyB());
            String awardName = normalizeName(award.winningBidder());
            if (contractName == null || awardName == null) {
                return List.of(ContractReviewFinding.notEvaluated(
                        configuration, null, "Supplier identity basis is incomplete"));
            }
            match = contractName.equals(awardName);
            basis = "normalized supplier name";
        }
        return List.of(match
                ? ContractReviewFinding.pass(
                        configuration, null, "MATCH", "MATCH",
                        "Contract supplier matches award supplier by " + basis)
                : ContractReviewFinding.fail(
                        configuration, null, "DIFFERENT", "MATCH",
                        "Contract supplier differs from award supplier by " + basis));
    }

    /**
     * 去除信用代码中的空白并统一为大写，以消除录入格式差异。
     *
     * @param value 原始统一社会信用代码，可为空
     * @return 规范化代码；缺失或空白时返回 {@code null}
     */
    private String normalizeCode(String value) {
        return value == null || value.isBlank()
                ? null
                : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 去除供应商名称空白并统一大小写，以支持无信用代码时的保守比较。
     *
     * @param value 原始供应商名称，可为空
     * @return 规范化名称；缺失或空白时返回 {@code null}
     */
    private String normalizeName(String value) {
        return com.fuquan.audit.contract.domain.ContractArithmetic.supplierName(value);
    }
}
