package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseAvailableResultsTest {
    @Test void distinctProjectMentionsStaySeparateAndRepeatedMentionsKeepTheirSources(){
        var a=identities("请示.pdf","项目名称：北辰修缮项目；项目名称：配套工程","projectName","北辰修缮项目","projectName","配套工程");
        var b=identities("磋商.pdf","项目名称：北辰修缮项目","projectName","北辰修缮项目");
        var result=details(a,b);
        assertThat(result).hasSize(2).anySatisfy(v->{assertThat(v.value()).isEqualTo("北辰修缮项目");assertThat(v.evidence()).hasSize(2);});
        assertThat(result).allSatisfy(v->assertThat(v.label()).isEqualTo("材料中的项目名称"));
        assertThat(result).noneSatisfy(v->assertThat(v.value()).contains(" / "));
    }
    private Document identities(String name,String text,String... pairs){
        var facts=new ArrayList<Fact>();for(int i=0;i<pairs.length;i+=2)facts.add(new Fact("r",pairs[i],pairs[i+1],"TEXT","p1",text,"SOURCE_VERIFIED","TEST","v1","CURRENT_PROJECT",null));
        return new Document(UUID.randomUUID(),name,12,"a".repeat(64),"key","EXTRACTED",List.of(new Segment("p1",text)),facts,"PDF",null,0);
    }
    private List<CaseAvailableResults.Detail> details(Document...docs){return CaseAvailableResults.details(new AuditCase(UUID.randomUUID(),"test",UUID.randomUUID(),"fp","PARTIAL",0,"token",Instant.EPOCH,1,Instant.EPOCH,List.of(docs),null));}
    @Test void contractUnifiedNumberIsRelabelledAndRepeatedProjectNumberHasAllSources(){
        var a=identities("审批.pdf","项目编号：XM-01；合同统一编号：HT-09","projectNo","XM-01","projectNo","HT-09");
        var b=identities("合同.pdf","项目编号：XM-01","projectNo","XM-01");
        assertThat(details(a,b)).hasSize(2).anySatisfy(d->{assertThat(d.label()).isEqualTo("合同编号");assertThat(d.value()).isEqualTo("HT-09");})
            .anySatisfy(d->{assertThat(d.label()).isEqualTo("项目编号");assertThat(d.value()).isEqualTo("XM-01");assertThat(d.evidence()).hasSize(2);});
    }
    @Test void actorFormattingDuplicatesMergeButAgencyAndRealBiddersStaySeparate(){
        var a=identities("采购.pdf","采购人：北辰市公安局（主管）；采购代理机构：晨星中心","purchaserName","北辰市公安局（主管）","supplierName","晨星中心");
        var b=identities("审批.pdf","采购人：北 辰 市 公 安 局","purchaserName","北 辰 市 公 安 局");
        var c=identities("投标A.pdf","投标人：晨星公司","supplierName","晨星公司");
        var d=identities("投标B.pdf","投标人：远景公司；乙方：乙方（承揽方）","supplierName","远景公司","supplierName","乙方（承揽方）");
        var result=details(a,b,c,d);
        assertThat(result.stream().filter(v->v.label().equals("采购单位")).toList()).singleElement().satisfies(v->assertThat(v.evidence()).hasSize(2));
        assertThat(result.stream().filter(v->v.label().equals("供应商")).map(CaseAvailableResults.Detail::value)).containsExactly("晨星公司","远景公司");
        assertThat(result).anySatisfy(v->{assertThat(v.label()).isEqualTo("采购代理机构");assertThat(v.value()).isEqualTo("晨星中心");});
    }
    @Test void annualQuoteIsVisibleWithoutInventingBudgetAwardOrWholeProjectComparison() {
        var d=new Document(UUID.randomUUID(),"投标文件.pdf",12,"a".repeat(64),"key","UNKNOWN",List.of(new Segment("p1","投标总价2200000元/年")),
            List.of(new Fact("r","quoteAmount","2200000元/年","MONEY","p1","投标总价2200000元/年","SOURCE_VERIFIED","TEST","v1","CURRENT_PROJECT","ANNUAL")),"PDF",null,0);
        var report=CaseFactPolicy.report(UUID.randomUUID(),List.of(d));
        var c=new AuditCase(UUID.randomUUID(),"test",UUID.randomUUID(),"fp","PARTIAL",0,"token",Instant.EPOCH,1,Instant.EPOCH,List.of(d),report);
        assertThat(CaseAvailableResults.details(c)).singleElement().satisfies(v->{assertThat(v.label()).isEqualTo("报价（每年）");assertThat(v.value()).isEqualTo("2200000元/年");assertThat(v.evidence().get(0).fileName()).isEqualTo("投标文件.pdf");});
        assertThat(report.budget()).isNull();assertThat(report.awardAmount()).isNull();assertThat(report.offers()).isEmpty();
        assertThat(CaseReportHtml.render(c)).contains("报价（每年）","2200000元/年");
    }
    @Test void declaredSupplierAliasDoesNotBlockQuoteButTwoRealSuppliersStayAmbiguous(){
        var d=identities("报价.pdf","供应商：晨星软件有限公司（以下简称“晨星”）；供应商：晨星；报价总额100元",
                "supplierName","晨星软件有限公司","supplierName","晨星","quoteAmount","100元");
        assertThat(CaseFactPolicy.scalar(d,"supplierName")).isEqualTo("晨星软件有限公司");
        assertThat(details(d).stream().filter(v->v.label().equals("供应商"))).singleElement()
                .satisfies(v->{assertThat(v.value()).isEqualTo("晨星软件有限公司");assertThat(v.evidence()).hasSize(1);});
        var two=identities("报价.pdf","供应商：晨星软件有限公司；供应商：远景公司","supplierName","晨星软件有限公司","supplierName","远景公司");
        assertThat(CaseFactPolicy.scalar(two,"supplierName")).isNull();
        assertThat(details(two)).hasSize(2);
    }
    @Test void unverifiedAndHistoricalCandidatesAreNotPresentedAsCurrentFacts() {
        var d=new Document(UUID.randomUUID(),"投标文件.pdf",12,"a".repeat(64),"key","UNKNOWN",List.of(new Segment("p1","历史业绩：东城客户，合同金额100元")),
            List.of(new Fact("r","supplierName","东城客户","TEXT","p1","历史业绩：东城客户","SOURCE_VERIFIED","TEST","v1","HISTORICAL","TOTAL"),
                    new Fact("r","budgetAmount","100元","MONEY","p1","合同金额100元","UNVERIFIED","TEST","v1")),"PDF",null,0);
        var c=new AuditCase(UUID.randomUUID(),"test",UUID.randomUUID(),"fp","PARTIAL",0,"token",Instant.EPOCH,1,Instant.EPOCH,List.of(d),null);
        assertThat(CaseAvailableResults.details(c)).isEmpty();
    }
    @Test void missingComparatorScopeDiffersFromAnExplicitOmittedRequiredAttachment() {
        var cover=new SourceEvidence("e",UUID.randomUUID(),"投标文件.pdf","a".repeat(64),"p1","投标文件封面");
        assertThat(CaseAvailableResults.scopeOnly("仅上传投标文件，未提供招标文件，建议补充完整招标文件。",List.of(cover))).isTrue();
        var requirement=new SourceEvidence("e",UUID.randomUUID(),"采购文件.pdf","a".repeat(64),"p2","响应时须提交检测证明附件，附件不得缺少。");
        assertThat(CaseAvailableResults.scopeOnly("未上传要求的附件证明。",List.of(requirement))).isFalse();
        assertThat(CaseAvailableResults.scopeOnly("采购文件未提供客观评分标准，条款规定由评委自由酌情评分。",List.of(cover))).isFalse();
    }
}
