package com.fuquan.audit.common.domain;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class RmbCurrencySuffixTest {
    @Test void currencySuffixesAndFinancialChineseKeepExactAmount(){
        for(String value:new String[]{"2200000人民币","2200000人民币。","2200000 CNY","人民币2200000元","贰佰贰拾万元整","220万元人民币"})
            assertThat(RmbAmountParser.parse(value)).as(value).hasValueSatisfying(v->assertThat(v).isEqualByComparingTo("2200000"));
    }
    @Test void unknownCurrenciesAndSentencesDoNotBecomeMoney(){
        for(String value:new String[]{"2200000美元","2200000欧元","预算2200000人民币","2200000元或2300000元","2200000人民币abc"})assertThat(RmbAmountParser.parse(value)).as(value).isEmpty();
    }
}
