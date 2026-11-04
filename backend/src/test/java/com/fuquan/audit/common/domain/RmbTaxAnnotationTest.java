package com.fuquan.audit.common.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RmbTaxAnnotationTest {
    @Test void sourceTaxAnnotationsPreserveExactValue(){
        for(String source:new String[]{"人民币叁拾陆万元整（含税）","360000元(不含税)","360000元（税前）。","360000元（税后）"})
            assertThat(RmbAmountParser.parse(source)).as(source).hasValueSatisfying(value->assertThat(value).isEqualByComparingTo("360000.00"));
    }
    @Test void annotationsCannotHideAlternativesDisagreementOrArithmetic(){
        for(String source:new String[]{"360000元（另加税）","360000元（含税390000元）","360000元（税率6%）","360000元（叁拾伍万元整）（含税）"})
            assertThat(RmbAmountParser.parse(source)).as(source).isEmpty();
    }
}
