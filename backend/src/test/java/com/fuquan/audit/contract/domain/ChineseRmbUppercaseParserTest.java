package com.fuquan.audit.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChineseRmbUppercaseParserTest {

    private final ChineseRmbUppercaseParser parser = new ChineseRmbUppercaseParser();

    @ParameterizedTest
    @CsvSource({
            "零元整,0.00",
            "人民币壹元整,1.00",
            "壹拾元整,10.00",
            "壹佰元整,100.00",
            "壹仟元整,1000.00",
            "壹万元整,10000.00",
            "壹亿元整,100000000.00",
            "壹佰元伍角,100.50",
            "壹佰元伍分,100.05",
            "壹佰元伍角陆分,100.56",
            "捌拾柒万陆仟元整,876000.00"
    })
    void parsesRequiredRmbUnits(String text, String expected) {
        assertThat(parser.parse(text)).contains(Money.of(new BigDecimal(expected)));
    }

    @Test
    void acceptsPrefixSpacesAndRoundVariants() {
        assertThat(parser.parse("人民币 壹佰 圆 正"))
                .contains(Money.of(new BigDecimal("100.00")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "", "壹佰", "壹元叁", "壹元角", "壹佰佰元整", "壹元壹角壹角", "人民币元整"
    })
    void rejectsMalformedOrUnsafeValues(String text) {
        assertThat(parser.parse(text)).isEmpty();
    }
}
