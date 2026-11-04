package com.fuquan.audit.contract.domain;

import java.util.List;

/**
 * 集中定义 F02 五条确定性规则的稳定编码及标准执行集合。
 *
 * <p>编码用于规则配置、结果持久化和 API 返回，不应随展示文案变化。</p>
 */
public final class F02RuleCodes {

    public static final String AMOUNT_WORDS_MATCH = "F02_AMOUNT_WORDS_MATCH";
    public static final String LINE_ITEM_ARITHMETIC = "F02_LINE_ITEM_ARITHMETIC";
    public static final String LINE_SUM_MATCH = "F02_LINE_SUM_MATCH";
    public static final String AWARD_AMOUNT_MATCH = "F02_AWARD_AMOUNT_MATCH";
    public static final String AWARD_SUPPLIER_MATCH = "F02_AWARD_SUPPLIER_MATCH";
    public static final List<String> ALL = List.of(
            AMOUNT_WORDS_MATCH,
            LINE_ITEM_ARITHMETIC,
            LINE_SUM_MATCH,
            AWARD_AMOUNT_MATCH,
            AWARD_SUPPLIER_MATCH);

    /**
     * 禁止实例化仅承载稳定规则编码的工具类。
     */
    private F02RuleCodes() {
    }
}
