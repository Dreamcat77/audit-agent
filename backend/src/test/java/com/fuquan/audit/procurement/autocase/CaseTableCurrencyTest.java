package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseTableCurrencyTest {
    @Test void arabicPriceWithCurrencyAndExactSuffixSurvivesQuotationValidation() {
        var d=doc("投标总报价：人民币2180000元整。");
        var fact=CaseFactPolicy.validate(d,reply("quoteAmount","人民币2180000元整","投标总报价：人民币2180000元整")).get(0);
        assertThat(fact.verification()).isEqualTo("SOURCE_VERIFIED");
        assertThat(ProcurementMaterialSemantics.money(d,fact)).hasValueSatisfying(v->assertThat(v).isEqualByComparingTo("2180000"));
    }
    Document doc(String pricePage){return new Document(UUID.randomUUID(),"投标文件.pdf",100,"a".repeat(64),"key","UNKNOWN",
        List.of(new Segment("p1",pricePage),new Segment("p2","历史业绩\n注册资本为1000万元，往年项目资金为300万元。")),List.of(),"PDF",null,0);}
    MaterialFieldAiPort.Result reply(String type,String value,String excerpt){return new MaterialFieldAiPort.Result(List.of(new Row("r",List.of(new MaterialFieldAiPort.Fact(type,value,"p1",excerpt,"CURRENT_PROJECT","TOTAL")))),"TEST","v5");}
    @Test void samePageQuotationUnitSurvivesUnrelatedHistoricalCapitalInTenThousandYuan() {
        var d=doc("2、开标一览表\n项目名称 建设期限 投标报价（元）\n平台建设部署 2200000.00\n全部报价以人民币表示，单位为元。");
        var values=CaseFactPolicy.validate(d,reply("quoteAmount","2200000.00","平台建设部署 2200000.00"));
        assertThat(values).singleElement().satisfies(f->{
            assertThat(f.verification()).isEqualTo("SOURCE_VERIFIED");
            assertThat(ProcurementMaterialSemantics.money(d,f)).hasValueSatisfying(v->assertThat(v).isEqualByComparingTo("2200000"));
            assertThat(d.segments().get(0).text()).contains(f.excerpt());assertThat(f.excerpt()).contains("元");
        });
        var withFacts=new Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),d.status(),d.segments(),values,d.parseMethod(),null,0);
        assertThat(CaseFactPolicy.amount(withFacts,"quoteAmount")).isEqualByComparingTo("2200000");
    }
    @Test void priceHeaderOnSamePageSupportsTotalAndExactDecimalScaling() {
        var d=doc("3.1 分项目明细报价表\n产品名称 价格（万元）\n教学平台 110.00\n运维平台 110.00\n合计 220.00");
        var fact=CaseFactPolicy.validate(d,reply("quoteAmount","220.00","合计 220.00")).get(0);
        assertThat(fact.verification()).isEqualTo("SOURCE_VERIFIED");
        assertThat(ProcurementMaterialSemantics.money(d,fact)).hasValueSatisfying(v->assertThat(v).isEqualByComparingTo("2200000"));
    }
    @Test void mixedLocalUnitsMissingUnitAndNumericSubstringsAreNotGuessed() {
        for(String page:List.of("报价（元）\n预算金额（万元）\n合计 220", "分项报价表\n合计 220")) {
            var d=doc(page);var fact=CaseFactPolicy.validate(d,reply("quoteAmount","220","合计 220")).get(0);
            assertThat(fact.verification()).isEqualTo("UNVERIFIED");
        }
        assertThat(CaseFactPolicy.validate(doc("预算金额22万元"),reply("budgetAmount","22","预算金额22万元")).get(0).verification()).isEqualTo("UNVERIFIED");
    }
    @Test void quotationTableCannotBeUsedToCreateBudgetOrAwardEvenWithModelScopeLabels() {
        var d=doc("开标一览表\n项目名称 投标报价（元）\n平台建设 2200000.00");
        for(String type:List.of("budgetAmount","awardAmount","ceilingAmount"))
            assertThat(CaseFactPolicy.validate(d,reply(type,"2200000.00","平台建设 2200000.00")).get(0).verification()).isEqualTo("UNVERIFIED");
    }
}
