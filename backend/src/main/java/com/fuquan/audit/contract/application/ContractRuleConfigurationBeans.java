package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ChineseRmbUppercaseParser;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.rule.AmountWordsMatchRule;
import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.contract.domain.rule.AwardSupplierMatchRule;
import com.fuquan.audit.contract.domain.rule.LineItemArithmeticRule;
import com.fuquan.audit.contract.domain.rule.LineSumMatchRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 F02 中文金额解析器和五条确定性 Java 规则。
 *
 * <p>该配置只组装纯 Java 规则，不引入 AI/OCR 外部服务依赖。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ContractRuleConfigurationBeans {

    /**
     * 提供规范中文大写人民币解析器。
     *
     * @return 无状态金额解析器
     */
    @Bean
    ChineseRmbUppercaseParser chineseRmbUppercaseParser() {
        return new ChineseRmbUppercaseParser();
    }

    /**
     * 提供合同金额大小写一致性规则。
     *
     * @param parser 中文大写人民币解析器
     * @return F02 金额大小写规则
     */
    @Bean
    ContractReviewRule amountWordsMatchRule(ChineseRmbUppercaseParser parser) {
        return new AmountWordsMatchRule(parser);
    }

    /**
     * 提供合同明细行算术一致性规则。
     *
     * @return F02 明细行算术规则
     */
    @Bean
    ContractReviewRule lineItemArithmeticRule() {
        return new LineItemArithmeticRule();
    }

    /**
     * 提供合同明细金额合计一致性规则。
     *
     * @return F02 明细合计规则
     */
    @Bean
    ContractReviewRule lineSumMatchRule() {
        return new LineSumMatchRule();
    }

    /**
     * 提供合同金额与中标金额一致性规则。
     *
     * @return F02 中标金额规则
     */
    @Bean
    ContractReviewRule awardAmountMatchRule() {
        return new AwardAmountMatchRule();
    }

    /**
     * 提供合同乙方与中标供应商一致性规则。
     *
     * @return F02 中标供应商规则
     */
    @Bean
    ContractReviewRule awardSupplierMatchRule() {
        return new AwardSupplierMatchRule();
    }
}
