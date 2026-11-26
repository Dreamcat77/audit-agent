package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

class CaseCoverageRegressionTest {
    private Document doc(String text,List<Fact> facts){return new Document(UUID.randomUUID(),"材料.pdf",10,"a".repeat(64),"storage","EXTRACTED",List.of(new Segment("p1",text)),facts,"PDF",null,1);}
    @Test void fourUnclassifiedDocumentsAllSupplyEvidenceEvenWithoutStructuredFacts(){
        var docs=List.of(doc("本项目资金预算2200000元，按项目实施范围使用。",List.of()),doc("交付验收完成后30日内付款，付款比例为90%。",List.of()),
                doc("评分标准：必须使用指定品牌，否则作无效响应处理。",List.of()),doc("报价含税总价2100000元，服务期限为一年。",List.of()));
        for(var module:CaseReviewContext.MODULES){
            var ctx=CaseReviewContext.select(module,docs);
            assertThat(ctx.scope()).hasSize(4).allSatisfy(s->assertThat(s.suppliedChars()).isPositive());
            assertThat(ctx.evidence().stream().map(SourceEvidence::documentId).distinct()).containsExactlyInAnyOrderElementsOf(docs.stream().map(Document::id).toList());
        }
        var withFailure=new ArrayList<>(docs);withFailure.set(1,docs.get(1).state("UNKNOWN","MODEL_TIMEOUT"));
        assertThat(CaseReviewContext.select("AGENT_PRECHECK",withFailure).evidence()).hasSize(4);
    }
    @Test void sourceVerifiedTemplateCannotEnterBlacklistOrMaterialDetailsEvenForPreviouslySavedFacts(){
        String name="[合同中心-供应商名称]";
        var d=doc("供应商名称："+name,List.of(new Fact("r","supplierName",name,"TEXT","p1","供应商名称："+name,"SOURCE_VERIFIED","TEST","v1")));
        assertThat(CaseFactPolicy.facts(d,"supplierName")).isEmpty();
        var blacklist=mock(BlacklistCheckPort.class);
        var review=new CaseBlacklistReview(blacklist,Clock.systemUTC(),mock(OperationAuditService.class)).check(UUID.randomUUID(),List.of(d),
                new CurrentUser("test","test","测试",Set.of("AUDITOR")));
        assertThat(review.checks()).isEmpty();verifyNoInteractions(blacklist);
        assertThat(CaseAvailableResults.details(new AuditCase(UUID.randomUUID(),"test",UUID.randomUUID(),"fp","COMPLETED",0,"",Instant.EPOCH,0,Instant.EPOCH,List.of(d),null))).isEmpty();
        assertThat(com.fuquan.audit.contract.domain.ContractPartyName.isPresent(name)).isFalse();
    }
    @Test void coverageSeparatesReadingAndExtractionFromSelectionForReview(){
        var d=doc("采购资金2200000元。",List.of());
        var coverage=CaseDocumentCoverage.of(List.of(d),null).get(d.id());
        assertThat(coverage.textChars()).isEqualTo(d.segments().get(0).text().length());
        assertThat(coverage.extractionCalls()).isEqualTo(1);
        assertThat(coverage.factCount()).isZero();assertThat(coverage.reviewInputChars()).isZero();
    }
    @Test void legacyBlacklistPlaceholderIsHiddenOnReadWithoutMutatingSavedReview(){
        var review=new ModuleReview("COMPLETED","digest","old",null,null,null,List.of(),List.of(),List.of(
                new ReviewCheck("SUPPLIER_BLACKLIST","NO_MATCH","[合同中心-供应商名称]未命中当前已接入的黑名单。",List.of()),
                new ReviewCheck("SUPPLIER_BLACKLIST","NO_MATCH","晨星信息中心未命中当前已接入的黑名单。",List.of())),List.of(),0,null);
        var report=new Report("DATA_INSUFFICIENT",null,null,List.of(),null,List.of(),Map.of("BLACKLIST","COMPLETED"),Map.of("BLACKLIST",review),null,null);
        var projected=CaseReviewAssurance.project(report);
        assertThat(projected.reviews().get("BLACKLIST").checks()).singleElement().satisfies(c->assertThat(c.explanation()).startsWith("晨星信息中心"));
        assertThat(report.reviews().get("BLACKLIST").checks()).hasSize(2);
    }
}
