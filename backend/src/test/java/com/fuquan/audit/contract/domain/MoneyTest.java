package com.fuquan.audit.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void valuesWithDifferentScalesAreEqualInMoneySemantics() {
        Money whole = Money.of(new BigDecimal("100"));
        Money oneDecimal = Money.of(new BigDecimal("100.0"));
        Money twoDecimals = Money.of(new BigDecimal("100.00"));

        assertThat(whole.sameValueAs(oneDecimal)).isTrue();
        assertThat(oneDecimal.sameValueAs(twoDecimals)).isTrue();
        assertThat(whole).isEqualTo(twoDecimals);
    }

    @Test
    void equalValuesHaveEqualHashCodes() {
        Set<Money> amounts = new HashSet<>();
        amounts.add(Money.of(new BigDecimal("100")));
        amounts.add(Money.of(new BigDecimal("100.00")));

        assertThat(amounts).hasSize(1);
    }
}
