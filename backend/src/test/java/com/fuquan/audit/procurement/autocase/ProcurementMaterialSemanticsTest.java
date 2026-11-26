package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcurementMaterialSemanticsTest {
    private Fact f(String type,String value,String locator,String excerpt){return new Fact("r",type,value,"TEXT",locator,excerpt,"SOURCE_VERIFIED","TEST","legacy");}
    private Document d(List<MaterialFieldAiPort.Segment> source,Fact... facts){return new Document(UUID.randomUUID(),"投标文件.pdf",100,"a".repeat(64),"key","EXTRACTED",source,List.of(facts),"PDF_TEXT",null,1);}
    private MaterialFieldAiPort.Segment s(String id,String text){return new MaterialFieldAiPort.Segment(id,text);}
    @Test void historicalAppendicesCannotBecomeCurrentProjectEvenWithLegacyFacts(){
        var doc=d(List.of(s("p1","投标文件\n项目名称：终端更新\n采购人：合成学校\n采购代理机构：合成代理"),
                s("p2","类似项目业绩\n项目名称：历史平台\n采购人：历史客户\n合同金额：900元"),
                s("p3","项目名称：往年扩容\n采购人：往年客户"),s("p4","第五章 报价一览表\n投标人：合成供应商\n投标总价：80元")),
                f("projectName","终端更新","p1","项目名称：终端更新"),f("projectName","历史平台","p2","项目名称：历史平台"),
                f("projectName","往年扩容","p3","项目名称：往年扩容"),f("purchaserName","合成学校","p1","采购人：合成学校"),
                f("purchaserName","合成代理","p1","采购代理机构：合成代理"),f("quoteAmount","80元","p4","投标总价：80元"));
        assertThat(CaseFactPolicy.facts(doc,"projectName")).extracting(Fact::value).containsExactly("终端更新");
        assertThat(CaseFactPolicy.scalar(doc,"purchaserName")).isEqualTo("合成学校");
        assertThat(CaseFactPolicy.scalar(doc,"agencyName")).isEqualTo("合成代理");
        assertThat(CaseFactPolicy.amount(doc,"quoteAmount")).isEqualByComparingTo("80");
        var ctx=CaseReviewContext.select("AGENT_PRECHECK",List.of(doc));
        assertThat(ctx.evidence()).noneMatch(e->e.excerpt().contains("历史客户")||e.excerpt().contains("往年客户"));
    }
    @Test void annualAndPackagePricesRemainVisibleButDoNotBecomeProjectTotals(){
        var annual=new Fact("r","quoteAmount","80元/年","MONEY","p1","每年投标总价：80元/年","SOURCE_VERIFIED","TEST","v4","CURRENT_PROJECT","ANNUAL");
        var pkg=new Fact("r","budgetAmount","100元","MONEY","p1","第1包预算金额：100元","SOURCE_VERIFIED","TEST","v4","CURRENT_PROJECT","PACKAGE");
        var doc=d(List.of(s("p1","投标文件\n每年投标总价：80元/年\n第1包预算金额：100元")),annual,pkg);
        assertThat(CaseFactPolicy.facts(doc,"quoteAmount")).hasSize(1);
        assertThat(CaseFactPolicy.amount(doc,"quoteAmount")).isNull();assertThat(CaseFactPolicy.amount(doc,"budgetAmount")).isNull();
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(doc)).offers()).isEmpty();
    }
    @Test void naturalSemanticAmountsWorkWithoutFixedLabelsButSubstringsAndOtherRolesDoNot(){
        String source="财政为本次采购安排人民币壹佰万元整。";
        var doc=d(List.of(s("p1",source)));
        var result=new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("r",List.of(
                new MaterialFieldAiPort.Fact("budgetAmount","人民币壹佰万元整","p1",source,"CURRENT_PROJECT","TOTAL")))),"TEST","v4");
        assertThat(CaseFactPolicy.validate(doc,result)).singleElement().extracting(Fact::verification).isEqualTo("SOURCE_VERIFIED");
        assertThat(ProcurementMaterialSemantics.amountRole("budgetAmount","100元","预算1100元","CURRENT_PROJECT","TOTAL")).isFalse();
        assertThat(ProcurementMaterialSemantics.amountRole("budgetAmount","100元","保证金100元","CURRENT_PROJECT","TOTAL")).isFalse();
    }
    @Test void allInclusiveOneYearAwardIsTotalWhileExplicitAnnualPriceRemainsAnnual(){
        String source="中标金额270000元，涵盖全部一年服务、人工及耗材费用。";
        var award=new Fact("r","awardAmount","270000元","MONEY","p1",source,"SOURCE_VERIFIED","TEST","v5","CURRENT_PROJECT","ANNUAL");
        var doc=d(List.of(s("p1",source)),award);
        assertThat(CaseFactPolicy.amount(doc,"awardAmount")).isEqualByComparingTo("270000");
        var annual=new Fact("r","quoteAmount","270000元","MONEY","p1","每年报价270000元，包含全部当年服务费用。","SOURCE_VERIFIED","TEST","v5","CURRENT_PROJECT","ANNUAL");
        assertThat(CaseFactPolicy.amount(d(List.of(s("p1",annual.excerpt())),annual),"quoteAmount")).isNull();
    }
    @Test void incompleteLineIsInsufficientRatherThanFalseArithmeticConflict(){
        var doc=d(List.of(s("p1","投标文件")),f("supplierName","合成供应商","p1","合成供应商"),f("quoteAmount","90元","p1","90元"),
                f("itemName","设备","p1","设备"),f("quantity","2","p1","2"));
        assertThat(CaseFactPolicy.report(UUID.randomUUID(),List.of(doc)).offers()).singleElement().extracting(Offer::lineValidation).isEqualTo("DATA_INSUFFICIENT");
    }
    @Test void tableHeaderUnitsUseExactDecimalAndKeepOriginalEvidence(){
        String source="预算金额（万元）\n22.50";
        var doc=d(List.of(s("p1",source)));
        var result=new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("r",List.of(
                new MaterialFieldAiPort.Fact("budgetAmount","22.50","p1",source,"CURRENT_PROJECT","TOTAL")))),"TEST","v4");
        var validated=CaseFactPolicy.validate(doc,result);
        assertThat(validated).singleElement().satisfies(f->{assertThat(f.value()).isEqualTo("22.50");assertThat(f.verification()).isEqualTo("SOURCE_VERIFIED");});
        var extracted=d(doc.segments(),validated.toArray(Fact[]::new));
        assertThat(CaseFactPolicy.amount(extracted,"budgetAmount")).isEqualByComparingTo("225000");
        assertThat(ProcurementMaterialSemantics.money(doc,"22","预算金额22万元")).isEmpty();
        assertThat(ProcurementMaterialSemantics.money(doc,"22","预算金额（万元）22；报价金额（元）22")).isEmpty();
    }
    @Test void generalPolicyAmountIsNotBudgetAndEquivalentCompanyTypographyDoesNotSplitSupplier(){
        var general=new Fact("r","budgetAmount","1000000元","MONEY","p1","一般项目1000000元以上需报告","SOURCE_VERIFIED","TEST","v4","GENERAL","TOTAL");
        var doc=d(List.of(s("p1","一般项目1000000元以上需报告")),general,
                f("supplierName","合成公司","p1","合成公司"),f("supplierName","合成\n公司","p1","合成\n公司"));
        assertThat(CaseFactPolicy.amount(doc,"budgetAmount")).isNull();
        assertThat(CaseFactPolicy.scalar(doc,"supplierName")).isEqualTo("合成公司");
    }
    @Test void topicSelectionKeepsTailScoringAndPaymentAndSharesCapacityAcrossFiles(){
        var many=new ArrayList<MaterialFieldAiPort.Segment>();many.add(s("cover","招标文件\n项目名称：终端更新"));
        for(int i=0;i<30;i++)many.add(s("p"+i,"项目介绍".repeat(1000)));
        many.add(s("score","评分办法：只接受本地企业的业绩证明，最高得30分。"));
        many.add(s("pay","付款条件：验收后支付90%，质保后支付10%。"));
        var doc=d(many);var ctx=CaseReviewContext.select("TENDER_COMPLIANCE",List.of(doc));
        assertThat(ctx.evidence()).anyMatch(e->e.locator().equals("score"));assertThat(ctx.evidence()).anyMatch(e->e.locator().equals("pay"));
        assertThat(ctx.gaps()).anyMatch(g->g.endsWith("CONTEXT_TRUNCATED"));
        var docs=new ArrayList<Document>();for(int i=0;i<12;i++)docs.add(d(many));
        var fair=CaseReviewContext.select("AGENT_PRECHECK",docs);
        assertThat(fair.scope()).allMatch(scope->scope.suppliedChars()>0);
        assertThat(fair.evidence().stream().mapToInt(e->e.excerpt().length()).sum()).isLessThanOrEqualTo(54000);
    }
    @Test void legacyFactJsonDoesNotNeedNewMetadata()throws Exception{
        var mapper=new ObjectMapper();var value=f("projectName","终端更新","p1","项目名称：终端更新");
        var node=mapper.valueToTree(value);((com.fasterxml.jackson.databind.node.ObjectNode)node).remove(List.of("subjectScope","amountScope"));
        assertThat(mapper.treeToValue(node,Fact.class).subjectScope()).isNull();
        // DM8 validates a hash of the serialized fact array. New optional keys
        // must not change the bytes or invalidate every historical snapshot.
        assertThat(mapper.writeValueAsString(mapper.treeToValue(node,Fact.class))).isEqualTo(mapper.writeValueAsString(node));
    }
}
