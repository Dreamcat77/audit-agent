package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Result;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

class CaseFactPolicyTest {
    @Test void oneSpreadsheetCanContainThreeIndependentlyGroundedSupplierQuotes() {
        var segments=new ArrayList<Segment>();var facts=new ArrayList<Fact>();
        String[] suppliers={"青榕设备服务有限公司","枫桥维护技术有限公司","星湾技术服务有限公司"};
        String[] amounts={"550000元","570000元","590000元"};
        for(int i=0;i<3;i++) {
            String locator="excel/sheet:1/row:"+(i+2),body=suppliers[i]+" | 报价金额："+amounts[i];
            segments.add(new Segment(locator,body));
            facts.add(new Fact("row"+i,"supplierName",suppliers[i],"TEXT",locator,body,"SOURCE_VERIFIED","test","v1","CURRENT_PROJECT",null));
            facts.add(new Fact("row"+i,"quoteAmount",amounts[i],"MONEY",locator,body,"SOURCE_VERIFIED","test","v1","CURRENT_PROJECT","TOTAL"));
        }
        var document=new Document(UUID.randomUUID(),"供应商询价汇总.xlsx",100,"a".repeat(64),"key","EXTRACTED",segments,facts,"POI",null,1);
        var report=CaseFactPolicy.report(UUID.randomUUID(),List.of(document,d("采购说明",f("budgetAmount","600000元"),f("ceilingAmount","580000元"))));
        assertThat(report.offers()).hasSize(3);
        assertThat(report.offers()).extracting(Offer::supplier).containsExactly(suppliers);
        assertThat(report.ceilingComparisons()).extracting(Report.QuoteComparison::status).containsExactly("WITHIN_CEILING","WITHIN_CEILING","OVER_CEILING");
        assertThat(report.calculation()).isNotNull();
    }
    @Test void aSharedModelRowCannotPairDifferentPhysicalCompanyAndPriceLines() {
        String locator="pdf/page:1/text/part:1";
        var rows=List.of(new Fact("same","supplierName","甲公司","TEXT",locator,"供应商：甲公司","SOURCE_VERIFIED","test","v1","CURRENT_PROJECT",null),
                new Fact("same","supplierName","乙公司","TEXT",locator,"供应商：乙公司","SOURCE_VERIFIED","test","v1","CURRENT_PROJECT",null),
                new Fact("same","quoteAmount","550000元","MONEY",locator,"报价：550000元","SOURCE_VERIFIED","test","v1","CURRENT_PROJECT","TOTAL"),
                new Fact("same","quoteAmount","590000元","MONEY",locator,"报价：590000元","SOURCE_VERIFIED","test","v1","CURRENT_PROJECT","TOTAL"));
        var doc=new Document(UUID.randomUUID(),"询价汇总.pdf",100,"a".repeat(64),"key","EXTRACTED",List.of(new Segment(locator,
                "供应商：甲公司\n供应商：乙公司\n报价：550000元\n报价：590000元")),rows,"PDFBOX",null,1);
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(doc)).offers()).isEmpty();
    }
    @Test void aCurrencyMarkerAfterDigitsDoesNotDiscardANaturalBudgetSentence(){
        String text="本项目的预算为2200000人民币元。最高限价：2100000元。";
        var doc=new Document(UUID.randomUUID(),"资金说明.pdf",1,"a".repeat(64),"key","PARSED",List.of(new Segment("page:1",text)),List.of(),"PDF",null,0);
        var response=new Result(List.of(new Row("meta",List.of(
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","2200000人民币元","page:1","本项目的预算为2200000人民币元。","CURRENT_PROJECT","TOTAL")
        ))),"test","v8");
        var verified=CaseFactPolicy.validate(doc,response);
        assertThat(verified).singleElement().satisfies(f->assertThat(f.verification()).isEqualTo("SOURCE_VERIFIED"));
    }
    @Test void usefulFactsDoNotTriggerWholeFileSupplementOnlyBecauseThereIsNoDocumentType(){
        var doc=d("资金安排",f("projectName","终端更新项目"),f("budgetAmount","2200000元"));
        assertThat(CaseFactPolicy.needsSupplement(doc)).isFalse();
        assertThat(CaseFactPolicy.needsSupplement(d("空材料"))).isTrue();
        var quote=d("报价单",f("documentType","报价单"),f("supplierName","合成供应商"));
        assertThat(CaseFactPolicy.needsSupplement(quote)).isTrue();
    }
    @Test void negotiationFundsAreKeptSeparateAndCannotReplaceTheTotalBudget(){
        String source="预算金额：2200000元（国库资金：2000000元，自筹资金：200000元）；合同履行期限：120日；采购方式：竞争性磋商";
        var doc=new Document(UUID.randomUUID(),"磋商.pdf",1,"a".repeat(64),"key","PARSED",List.of(new Segment("page:1",source)),List.of(),"PDF",null,0);
        var reply=new Result(List.of(new Row("meta",List.of(
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","2200000元","page:1",source),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("treasuryFundAmount","2000000元","page:1",source),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("selfFundAmount","200000元","page:1",source),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("budgetAmount","2000000元","page:1",source),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("procurementMethod","竞争性磋商","page:1",source),
            new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("contractPerformancePeriod","120日","page:1",source)
        ))),"test","v8");
        var facts=CaseFactPolicy.validate(doc,reply);
        assertThat(facts.stream().filter(f->f.verification().equals("SOURCE_VERIFIED")).map(Fact::type))
            .contains("budgetAmount","treasuryFundAmount","selfFundAmount","procurementMethod","contractPerformancePeriod");
        assertThat(facts.stream().filter(f->f.type().equals("budgetAmount")&&f.verification().equals("SOURCE_VERIFIED")).map(Fact::value))
            .containsExactly("2200000元");
    }
    @Test void currentAwardInTenderSummarySurvivesDocumentClassificationWithoutUsingQuotesAsAwards() {
        String text="招标文件\n预算金额2200000元；最高限价2100000元。\n本次中标金额2050000元；投标总报价2150000元。";
        var parsed=new Document(UUID.randomUUID(),"招标文件.pdf",1,"a".repeat(64),"b".repeat(64),"PARSED",List.of(new Segment("p1",text)),List.of(),"PDF",null,0);
        var reply=new Result(List.of(new Row("meta",List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("documentType","招标文件","p1","招标文件"),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("awardAmount","2050000元","p1","本次中标金额2050000元"),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("awardAmount","2150000元","p1","投标总报价2150000元")))),"test","v1");
        var verified=new Document(parsed.id(),parsed.name(),1,parsed.sha256(),parsed.storageKey(),"EXTRACTED",parsed.segments(),CaseFactPolicy.validate(parsed,reply),"PDF",null,1);
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(verified)).awardAmount()).isEqualByComparingTo("2050000");
        var conflict=CaseFactPolicy.report(UUID.randomUUID(),List.of(verified,d("其他材料",f("awardAmount","2060000元"))));
        assertThat(conflict.awardAmount()).isNull();
    }
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
    @Test void batchIsOneProjectAndDifferentReferenceNumbersDoNotEraseCeiling() {
        var a=d("a",f("projectNo","A"),f("ceilingAmount","350000元"));
        var b=d("b",f("projectNo","B"),f("supplierName","供应商"),f("quoteAmount","360000元"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(a)).budget()).isNull();
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(a,b)).ceilingAmount()).isEqualByComparingTo("350000");
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
    @Test void templateRoleLabelsAndPlaceholderKeysNeverBecomeBusinessParties() {
        String source="乙方（承揽方）：乙方；供应商：[合同中心-供应商名称]；采购单位：${purchaserName}";
        var doc=new Document(UUID.randomUUID(),"合同模板.pdf",1,"a".repeat(64),"b".repeat(64),"PARSED",
                List.of(new Segment("p1",source)),List.of(),"PDF",null,0);
        var result=new Result(List.of(new Row("one",List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("supplierName","乙方（承揽方）","p1","乙方（承揽方）：乙方"),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("supplierName","[合同中心-供应商名称]","p1","供应商：[合同中心-供应商名称]"),
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("purchaserName","${purchaserName}","p1","采购单位：${purchaserName}")))) ,"test","v1");
        assertThat(CaseFactPolicy.validate(doc,result)).isEmpty();
    }
    @Test void validPartyBesideUnfilledTemplatePlaceholderIsNotDiscarded() {
        String source="采购人：示例市公安局；供应商：[合同中心-供应商名称]";
        var doc=new Document(UUID.randomUUID(),"需求文件.pdf",1,"a".repeat(64),"b".repeat(64),"PARSED",
                List.of(new Segment("p1",source)),List.of(),"PDF",null,0);
        var result=new Result(List.of(new Row("one",List.of(
                new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact("purchaserName","示例市公安局","p1",source)))) ,"test","v1");
        assertThat(CaseFactPolicy.validate(doc,result)).filteredOn(f->f.verification().equals("SOURCE_VERIFIED"))
                .extracting(Fact::value).containsExactly("示例市公安局");
    }
    @Test void placeholderChecksApplyToTheExtractedValueOnly() {
        assertThat(CaseFactPolicy.isPlaceholder("supplierName", "${supplierName}")).isTrue();
        assertThat(CaseFactPolicy.isPlaceholder("supplierName", "[合同中心-供应商名称]")).isTrue();
        assertThat(CaseFactPolicy.isPlaceholder("purchaserName", "甲方：甲方")).isTrue();
        assertThat(CaseFactPolicy.isPlaceholder("supplierName", "甲方科技有限公司")).isFalse();
        assertThat(CaseFactPolicy.isPlaceholder("purchaserName", "示例市公安局")).isFalse();
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
        assertThat(r.notices()).noneMatch(n->n.contains("编号冲突"));
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
    @Test void quotedRowAmountsCanBeSummedWithoutInventingUnitPrices() {
        var q=d("报价明细",f("supplierName","示例科技有限公司"),f("quoteAmount","100元"),
                new Fact("one","itemName","扫描服务","TEXT","p1","扫描服务","SOURCE_VERIFIED","TEST","v1"),
                new Fact("one","quantity","2","DECIMAL","p1","2","SOURCE_VERIFIED","TEST","v1"),
                new Fact("one","lineAmount","60元","MONEY","p1","60元","SOURCE_VERIFIED","TEST","v1"),
                new Fact("two","itemName","质检服务","TEXT","p2","质检服务","SOURCE_VERIFIED","TEST","v1"),
                new Fact("two","quantity","1","DECIMAL","p2","1","SOURCE_VERIFIED","TEST","v1"),
                new Fact("two","lineAmount","40元","MONEY","p2","40元","SOURCE_VERIFIED","TEST","v1"));
        var offer=CaseFactPolicy.report(UUID.randomUUID(),List.of(q)).offers().get(0);
        assertThat(offer.lines()).hasSize(2).allMatch(l->l.unitPrice()==null);
        assertThat(offer.lineValidation()).isEqualTo("SUM_VERIFIED");
        var wrong=d("报价明细",f("supplierName","示例科技有限公司"),f("quoteAmount","99元"),
                f("itemName","扫描服务"),f("quantity","2"),f("lineAmount","100元"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(wrong)).offers().get(0).lineValidation()).isEqualTo("CONFLICT");
    }
}
