package com.fuquan.audit.contract.domain.rule;

import com.fuquan.audit.contract.domain.ChineseRmbUppercaseParser;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.Money;
import java.util.List;
import java.util.Optional;

/**
 * F02 合同金额大小写一致性规则。
 *
 * <p>规则严格解析中文大写金额后与合同数字金额作零容差比较；文本缺失或不规范时返回 NOT_EVALUATED。</p>
 */
public final class AmountWordsMatchRule implements ContractReviewRule {

    private final ChineseRmbUppercaseParser parser;

    /**
     * 创建使用指定中文金额解析器的规则。
     *
     * @param parser 规范中文大写人民币解析器
     */
    public AmountWordsMatchRule(ChineseRmbUppercaseParser parser) {
        this.parser = parser;
    }

    /**
     * 返回合同金额大小写一致性规则编码。
     *
     * @return {@code F02_AMOUNT_WORDS_MATCH}
     */
    @Override
    public String ruleCode() {
        return F02RuleCodes.AMOUNT_WORDS_MATCH;
    }

    /**
     * 比较合同中文大写金额与数字合同金额。
     *
     * @param context 包含合同金额事实的审查上下文
     * @param configuration 本次执行的风险、阻断和版本配置
     * @return 单条 PASS、FAIL 或 NOT_EVALUATED 结论
     */
    @Override
    public List<ContractReviewFinding> evaluate(
            ContractReviewContext context,
            ContractRuleConfiguration configuration) {
        Optional<Money> parsed = parser.parse(context.contract().amountInWords());
        if (parsed.isEmpty()) {
            return List.of(ContractReviewFinding.notEvaluated(
                    configuration, null, "Amount in words is missing or invalid"));
        }
        Money numeric = context.contract().contractAmount();
        return List.of(parsed.get().sameValueAs(numeric)
                ? ContractReviewFinding.pass(
                        configuration, null, parsed.get().toString(), numeric.toString(),
                        "Amount in words matches numeric contract amount")
                : ContractReviewFinding.fail(
                        configuration, null, parsed.get().toString(), numeric.toString(),
                        "Amount in words does not match numeric contract amount"));
    }
}
