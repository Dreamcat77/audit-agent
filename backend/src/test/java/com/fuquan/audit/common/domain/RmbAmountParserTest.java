package com.fuquan.audit.common.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RmbAmountParserTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分|123456.78", "壹拾贰万叁仟肆佰伍拾陆元柒角捌分整|123456.78",
        "貳拾參萬陸仟圓正|236000.00", "壹亿零壹万零壹元零伍分|100010001.05",
        "壹仟零壹拾元整|1010.00", "零元整|0.00", "壹元伍角|1.50",
        "拾万元|100000.00", "壹拾贰万元整|120000.00", "壹佰|100.00",
        "１２．３４万元|123400.00", "120,000.00元|120000.00",
        "120000元（大写：壹拾贰万元整）|120000.00", "0.0001万元|1.00",
        "9999999999999999.99|9999999999999999.99", "大写：壹佰元整|100.00", "人民币壹佰元|100.00",
        "12万|120000.00", "1.5千|1500.00", "１００．５|100.50", "CNY 123.45|123.45",
        "壹拾万元整。|100000.00", "10000元。|10000.00", "壹元伍角；|1.50",
        "人民币壹拾贰万元整（120000.00元）|120000.00",
        "100000.00元（人民币壹拾万元整）|100000.00",
        "人民币壹元贰角叁分（1.23元）。|1.23"
        ,"人民币2180000元整|2180000.00", "2,180,000.00元整|2180000.00", "220万元整|2200000.00", "100.50元正|100.50"
    }) void recognizesExactAmounts(String raw,String expected) {
        assertThat(RmbAmountParser.parse(raw)).hasValueSatisfying(v->assertThat(v).isEqualByComparingTo(expected));
    }
    @ParameterizedTest @ValueSource(strings={"壹佰贰元整","壹贰元整","壹佰佰元整","壹万亿元整",
        "壹元贰分叁角","壹元整贰角","壹元贰角叁分肆厘","负壹元整","-100","0.001元",
        "10000000000000000","1e3","1,2元","1美元","壹元或贰元","", "100整", "100元整另加20元", "0.001元整",
        "120000元（大写：壹拾万元整）","壹仟元整（大写：壹佰元整）",
        "10000.。", "壹拾万元整。另批贰万元", "壹拾万元整。。", "10000元待定",
        "壹拾贰万元整（100000.00元）", "壹拾贰万元整（120000.00元）另加100元",
        "100元（100元）", "壹拾万元整（壹拾万元整）", "壹元整（1.001元）",
        "壹元整（（1元））", "壹元整（-1元）"})
    void ambiguousMalformedMismatchAndOverflowFailClosed(String raw) {assertThat(RmbAmountParser.parse(raw)).isEmpty();}
}
