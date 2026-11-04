package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Result;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

class CaseFactPolicyTest {
    private Fact f(String type,String value){return new Fact("meta",type,value,"TEXT","page:1",value,"SOURCE_VERIFIED","TEST","v1");}
    private Document d(String name,Fact... facts){return new Document(UUID.randomUUID(),name,1,"a".repeat(64),"b".repeat(64),"EXTRACTED",List.of(),List.of(facts),"TEXT",null,1);}
    @Test void verifiedResponseLettersDoNotRequireAFixedQuotationDocumentType() {
        var response=d("响应文件",f("documentType","响应文件"),f("supplierName","合成供应商"),f("quoteAmount","2150000元"));
        var tender=d("招标文件",f("budgetAmount","2200000元"),f("ceilingAmount","2100000元"));
        var r=CaseFactPolicy.report(UUID.randomUUID(),List.of(response,tender));
        assertThat(r.offers()).hasSize(1);assertThat(r.ceilingAmount()).isEqualByComparingTo("2100000");
        assertThat(r.quoteComparisons().get(0).status()).isEqualTo("WITHIN_BUDGET");
        assertThat(r.ceilingComparisons().get(0).status()).isEqualTo("OVER_CEILING");
        assertThat(r.ceilingComparisons().get(0).difference()).isEqualByComparingTo("50000");
        var reviewed=r.review("TEST",new ModuleReview("COMPLETED","d","v","m",null,null,List.of(),List.of(),List.of(),List.of(),0,null));
        assertThat(reviewed.ceilingAmount()).isEqualByComparingTo(r.ceilingAmount());
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(d("说明",f("supplierName","供应商"),f("budgetAmount","100元")))).offers()).isEmpty();
    }
    @Test void conflictingProjectsDoNotShareCeilingsAndLimitsNeverBecomeBudgets() {
        var a=d("a",f("projectNo","A"),f("ceilingAmount","350000元"));
        var b=d("b",f("projectNo","B"),f("supplierName","供应商"),f("quoteAmount","360000元"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(a)).budget()).isNull();
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(a,b)).ceilingAmount()).isNull();
    }
    @Test void inventedValueForeignLocatorAndRelabelledBudgetCannotBecomeQuote() {
        String source="报价单 含税报价合计：人民币100元；预算金额：200元";
        var doc=new Document(UUID.randomUUID(),"x.pdf",1,"a".repeat(64),"b".repeat(64),"PARSED",List.of(new Segment("page:1",source)),List.of(),"PDF",null,0);
        var result=new Result(List.of(new Row("one",List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("quoteAmount","200元","page:1",source),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("supplierName","虚构公司","page:1",source),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("quoteAmount","100元","foreign",source)))),"test","v1");
        assertThat(CaseFactPolicy.validate(doc,result)).allMatch(f->f.verification().equals("UNVERIFIED"));
    }
    @Test void noBudgetKeepsOffersWithoutMarkingOverBudgetCheckAsClear() {
        var quote=d("q.pdf",f("documentType","报价单"),f("supplierName","甲公司"),f("quoteAmount","100元"));
        var report=CaseFactPolicy.report(UUID.randomUUID(),List.of(quote));
        assertThat(report.budget()).isNull();assertThat(report.offers()).hasSize(1);assertThat(report.calculation()).isNull();
        assertThat(report.quoteStatus()).isEqualTo("DATA_INSUFFICIENT");assertThat(report.modules().get("BLACKLIST")).isEqualTo("NOT_EXECUTED");
    }
    @Test void budgetAndAwardMustBindToTheirOwnAmountAndAmbiguousTableUnitsStayUnknown() {
        String source="预算金额：100元；中标金额：90元；付款金额：50元；表格单位：万元，预算金额：100";
        var doc=new Document(UUID.randomUUID(),"x.pdf",1,"a".repeat(64),"b".repeat(64),"PARSED",List.of(new Segment("p1",source)),List.of(),"PDF",null,0);
        var result=new Result(List.of(new Row("one",List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","50元","p1",source),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("awardAmount","50元","p1",source),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","100","p1",source),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","100元","p1",source)))),"test","v1");
        var facts=CaseFactPolicy.validate(doc,result);
        assertThat(facts).filteredOn(f->f.verification().equals("SOURCE_VERIFIED")).extracting(Fact::value).containsExactly("100元");
    }
    @Test void missingBudgetAndAwardReceiveABoundedSupplementAttempt() {
        assertThat(CaseFactPolicy.needsSupplement(d("budget",f("documentType","预算批复")))).isTrue();
        assertThat(CaseFactPolicy.needsSupplement(d("award",f("documentType","中标通知书")))).isTrue();
        assertThat(CaseFactPolicy.needsSupplement(d("budget",f("documentType","预算批复"),f("budgetAmount","100元")))).isFalse();
    }
    @Test void explicitTenderBudgetDoesNotRequireASeparateApprovalFileAndConflictsAreNotHidden() {
        var tender=d("招标文件.pdf",f("documentType","招标文件"),f("budgetAmount","100元"));
        var quote=d("q",f("documentType","报价单"),f("supplierName","甲公司"),f("quoteAmount","90元"));
        var r=CaseFactPolicy.report(UUID.randomUUID(),List.of(tender,quote));
        assertThat(r.budget()).isEqualByComparingTo("100");assertThat(r.offers().get(0).amount()).isEqualByComparingTo("90");
        var conflicting=d("需求.pdf",f("documentType","需求申请"),f("budgetAmount","200元"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(tender,conflicting,quote)).budget()).isNull();
        var ceiling=d("限价.pdf",f("documentType","招标文件"),f("ceilingAmount","100元"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(ceiling,quote)).budget()).isNull();
    }
    @Test void conflictingBudgetsAndDifferentProjectsDoNotSelectConvenientValues() {
        var b=d("b.pdf",f("documentType","预算批复"),f("budgetAmount","100元"),f("budgetAmount","200元"),f("projectNo","A"));
        var q=d("q.pdf",f("documentType","报价单"),f("supplierName","公司"),f("quoteAmount","90元"),f("projectNo","B"));
        var r=CaseFactPolicy.report(UUID.randomUUID(),List.of(b,q));assertThat(r.budget()).isNull();assertThat(r.calculation()).isNull();
        assertThat(r.notices()).anyMatch(n->n.contains("编号冲突"));
    }
    @Test void duplicateSupplierAndAwardDocumentAreNotThreeIndependentQuotes() {
        var budget=d("b",f("documentType","预算批复"),f("budgetAmount","100元"));
        var q1=d("q1",f("documentType","报价单"),f("supplierName","甲公司"),f("quoteAmount","90元"));
        var q2=d("q2",f("documentType","报价单"),f("supplierName","甲公司"),f("quoteAmount","95元"));
        var award=d("award",f("documentType","中标通知书"),f("supplierName","乙公司"),f("awardAmount","90元"),f("quoteAmount","90元"));
        var r=CaseFactPolicy.report(UUID.randomUUID(),List.of(budget,q1,q2,award));
        assertThat(r.offers()).hasSize(2);assertThat(r.calculation()).isNull();assertThat(r.awardAmount()).isEqualByComparingTo("90");
    }
    @Test void lineArithmeticMismatchNeverCorrectsOriginalQuotedTotal() {
        var q=d("q",f("documentType","报价单"),f("supplierName","公司"),f("quoteAmount","99元"),
                f("itemName","设备"),f("quantity","2"),f("unitPrice","20元"),f("lineAmount","99元"));
        var r=CaseFactPolicy.report(UUID.randomUUID(),List.of(q));
        assertThat(r.offers().get(0).lineValidation()).isEqualTo("CONFLICT");assertThat(r.offers().get(0).amount()).isEqualByComparingTo("99");
    }
}
