package com.fuquan.audit.procurement.application;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProcurementNaturalQuotationTest {
 @Test void naturalTotalLabelsRetainExactRmbRole() {
  for(String label:new String[]{"投标报价总额","投标报价总金额","响应报价金额","报价总价","含税报价合计"})
   assertThat(ProcurementBidQuotationExtractor.extract(label+"：2000000元")).isEqualByComparingTo("2000000");
 }
 @Test void budgetUnitPriceAndConflictingVersionsAreNotFinalQuotes() {
  for(String source:new String[]{"项目预算：2200000元","商品单价：20000元","投标报价总额：2000000元；响应报价总额：2100000元"})
   assertThat(ProcurementBidQuotationExtractor.extract(source)).isNull();
 }
 @Test void responseLettersAndTaxAnnotationsRemainExplicitQuotes() {
  assertThat(ProcurementBidQuotationExtractor.extract("投标总报价（含税）：人民币贰佰壹拾伍万元整。")).isEqualByComparingTo("2150000");
  assertThat(ProcurementBidQuotationExtractor.extract("本次最终含税报价为叁拾陆万元整。")).isEqualByComparingTo("360000");
 }
}
