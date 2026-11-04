package com.fuquan.audit.contract.domain.rule;

import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.util.List;

/**
 * F02 合同金额与中标金额一致性规则。
 *
 * <p>规则以结构化中标依据为唯一期望值来源；依据缺失时不猜测结果，而返回 NOT_EVALUATED。</p>
 */
public final class AwardAmountMatchRule implements ContractReviewRule {

    /**
     * 返回合同金额与中标金额一致性规则编码。
     *
     * @return {@code F02_AWARD_AMOUNT_MATCH}
     */
    @Override
    public String ruleCode() {
        return F02RuleCodes.AWARD_AMOUNT_MATCH;
    }

    /**
     * 对合同金额和中标金额进行 BigDecimal 数值语义的零容差比较。
     *
     * @param context 包含合同与中标依据的审查上下文
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
                    configuration, null, "Award notice amount basis is missing"));
        }
        return List.of(award.winningAmount().sameValueAs(context.contract().contractAmount())
                ? ContractReviewFinding.pass(
                        configuration, null, context.contract().contractAmount().toString(),
                        award.winningAmount().toString(), "Contract amount matches award amount")
                : ContractReviewFinding.fail(
                        configuration, null, context.contract().contractAmount().toString(),
                        award.winningAmount().toString(), "Contract amount differs from award amount"));
    }
}
