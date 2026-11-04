package com.fuquan.audit.contract.domain.rule;

import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.math.BigDecimal;
import java.util.List;

/**
 * F02 合同明细金额合计一致性规则。
 *
 * <p>规则汇总各行声明金额并与合同总金额作零容差比较，避免用浮点数或展示格式参与业务判断。</p>
 */
public final class LineSumMatchRule implements ContractReviewRule {

    /**
     * 返回明细合计与合同总金额一致性规则编码。
     *
     * @return {@code F02_LINE_SUM_MATCH}
     */
    @Override
    public String ruleCode() {
        return F02RuleCodes.LINE_SUM_MATCH;
    }

    /**
     * 汇总合同明细声明金额并与合同总金额比较。
     *
     * @param context 包含合同金额和明细的审查上下文
     * @param configuration 本次执行的风险、阻断和版本配置
     * @return 单条 PASS、FAIL 或没有明细时的 NOT_EVALUATED 结论
     */
    @Override
    public List<ContractReviewFinding> evaluate(
            ContractReviewContext context,
            ContractRuleConfiguration configuration) {
        if (context.contract().lineItems().isEmpty()) {
            return List.of(ContractReviewFinding.notEvaluated(
                    configuration, null, "Contract has no line totals to sum"));
        }
        BigDecimal actual = com.fuquan.audit.contract.domain.ContractArithmetic.sum(context.contract().lineItems().stream()
                .map(item -> item.declaredAmount().amount()).toList());
        BigDecimal expected = context.contract().contractAmount().amount();
        return List.of(actual.compareTo(expected) == 0
                ? ContractReviewFinding.pass(
                        configuration, null, plain(actual), plain(expected),
                        "Line item sum matches contract amount")
                : ContractReviewFinding.fail(
                        configuration, null, plain(actual), plain(expected),
                        "Line item sum differs from contract amount"));
    }

    /**
     * 将十进制金额规范为不含无意义尾零的证据文本。
     *
     * @param value 待展示的精确十进制值
     * @return 普通十进制文本
     */
    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
