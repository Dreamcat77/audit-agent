package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseAvailableResultsTest {
    @Test void annualQuoteIsVisibleWithoutInventingBudgetAwardOrWholeProjectComparison() {
        var d=new Document(UUID.randomUUID(),"投标文件.pdf",12,"a".repeat(64),"key","UNKNOWN",List.of(new Segment("p1","投标总价2200000元/年")),
            List.of(new Fact("r","quoteAmount","2200000元/年","MONEY","p1","投标总价2200000元/年","SOURCE_VERIFIED","TEST","v1","CURRENT_PROJECT","ANNUAL")),"PDF",null,0);
        var report=CaseFactPolicy.report(UUID.randomUUID(),List.of(d));
        var c=new AuditCase(UUID.randomUUID(),"test",UUID.randomUUID(),"fp","PARTIAL",0,"token",Instant.EPOCH,1,Instant.EPOCH,List.of(d),report);
        assertThat(CaseAvailableResults.details(c)).singleElement().satisfies(v->{assertThat(v.label()).isEqualTo("报价（每年）");assertThat(v.value()).isEqualTo("2200000元/年");assertThat(v.evidence().get(0).fileName()).isEqualTo("投标文件.pdf");});
        assertThat(report.budget()).isNull();assertThat(report.awardAmount()).isNull();assertThat(report.offers()).isEmpty();
        assertThat(CaseReportHtml.render(c)).contains("报价（每年）","2200000元/年");
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
