package com.fuquan.audit.procurement.autocase;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
class CaseNaturalBudgetTest {
    AuditCase.Fact check(String field,String value,String source){
        var doc=new Document(UUID.randomUUID(),"tender.pdf",10,"a".repeat(64),"key","PARSED",List.of(new Segment("p3",source)),List.of(),"OFFICE_TEXT",null,0);
        var reply=new Result(List.of(new Row("r",List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact(field,value,"p3",source)))),"test","v1");
        return CaseFactPolicy.validate(doc,reply).get(0);
    }
    @Test void acceptsNaturalBudgetLabelsWithCurrencySuffix(){
        for(String sentence:List.of("本项目的预算为2200000人民币。","本项目预算总金额为人民币2200000人民币。","本项目预算价为2200000人民币。"))
            assertThat(check("budgetAmount","2200000人民币",sentence).verification()).as(sentence).isEqualTo("SOURCE_VERIFIED");
    }
    @Test void budgetQuotationAwardCannotBeSwappedOrMatchedToPrefix(){
        assertThat(check("budgetAmount","2200000元","投标报价为2200000元").verification()).isEqualTo("UNVERIFIED");
        assertThat(check("quoteAmount","2200000元","本项目预算为2200000元").verification()).isEqualTo("UNVERIFIED");
        assertThat(check("budgetAmount","220000元","预算为2200000元").verification()).isEqualTo("UNVERIFIED");
        assertThat(check("budgetAmount","22","预算为22万元").verification()).isEqualTo("UNVERIFIED");
    }
    @Test void acceptsTaxAnnotatedBudgetAndTenderCeilingWithoutSwappingRoles(){
        assertThat(check("budgetAmount","人民币叁拾陆万元整（含税）","本项目采购预算为人民币叁拾陆万元整（含税）").verification()).isEqualTo("SOURCE_VERIFIED");
        for(String label:List.of("最高投标限价","最高响应限价","投标最高限价"))
            assertThat(check("ceilingAmount","350000元",label+"为350000元").verification()).as(label).isEqualTo("SOURCE_VERIFIED");
        assertThat(check("ceilingAmount","350000元","投标保证金为350000元").verification()).isEqualTo("UNVERIFIED");
        assertThat(check("budgetAmount","350000元","最高投标限价为350000元").verification()).isEqualTo("UNVERIFIED");
    }
}
