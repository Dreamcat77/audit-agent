package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetTextNormalizerTest {

    @Test
    void normalizesChineseWhitespacePunctuationAndWidth() {
        assertThat(BudgetTextNormalizer.subject("  办公　设备，及耗材采购  "))
                .isEqualTo("办公设备及耗材采购");
    }

    @Test
    void parsesCurrencyCommasAndUnitsAsYuan() {
        assertThat(BudgetTextNormalizer.amount("￥12,345.60"))
                .isEqualByComparingTo("12345.60");
        assertThat(BudgetTextNormalizer.amount("1.5万元"))
                .isEqualByComparingTo("15000.00");
        assertThat(BudgetTextNormalizer.amount("人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分"))
                .isEqualByComparingTo("123456.78");
    }
    @Test void neverRoundsSubCentSourceAmounts() {
        assertThat(BudgetTextNormalizer.amount("0.001元")).isNull();
    }

    @Test
    void returnsNullForInvalidAmount() {
        assertThat(BudgetTextNormalizer.amount("待定")).isNull();
        assertThat(BudgetTextNormalizer.amount("")).isNull();
    }
}
