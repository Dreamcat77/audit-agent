package com.fuquan.audit.contract.domain.rule;

import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * F02 合同明细行算术一致性规则。
 *
 * <p>规则逐行校验“数量 × 单价 = 声明行金额”，每个明细独立形成结论，便于定位具体差异。</p>
 */
public final class LineItemArithmeticRule implements ContractReviewRule {

    /**
     * 返回明细行算术一致性规则编码。
     *
     * @return {@code F02_LINE_ITEM_ARITHMETIC}
     */
    @Override
    public String ruleCode() {
        return F02RuleCodes.LINE_ITEM_ARITHMETIC;
    }

    /**
     * 为合同每条明细比较计算金额与声明金额。
     *
     * @param context 包含合同明细的审查上下文
     * @param configuration 本次执行的风险、阻断和版本配置
     * @return 按明细生成的结论；没有明细时返回单条 NOT_EVALUATED
     */
    @Override
    public List<ContractReviewFinding> evaluate(
            ContractReviewContext context,
            ContractRuleConfiguration configuration) {
        if (context.contract().lineItems().isEmpty()) {
            return List.of(ContractReviewFinding.notEvaluated(
                    configuration, null, "Contract has no line item basis"));
        }
        List<ContractReviewFinding> findings = new ArrayList<>();
        for (ContractLineItem item : context.contract().lineItems()) {
            if (item.basis() == com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY) {
                findings.add(ContractReviewFinding.notEvaluated(configuration, item.itemKey(),
                        "Line item has declared amount only; quantity and unit price are unavailable"));
                continue;
            }
            BigDecimal calculated = com.fuquan.audit.contract.domain.ContractArithmetic.lineAmount(item.quantity(), item.unitPrice());
            BigDecimal declared = item.declaredAmount().amount();
            findings.add(calculated.compareTo(declared) == 0
                    ? ContractReviewFinding.pass(
                            configuration, item.itemKey(), plain(declared), plain(calculated),
                            "Line quantity multiplied by unit price matches declared amount")
                    : ContractReviewFinding.fail(
                            configuration, item.itemKey(), plain(declared), plain(calculated),
                            "Line declared amount differs from quantity multiplied by unit price"));
        }
        return List.copyOf(findings);
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
