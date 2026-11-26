package com.fuquan.audit.procurement.autocase;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import static org.assertj.core.api.Assertions.*;
class CaseFindingPresentationTest {
    @Test void noEvidenceDoesNotEstablishIssueEquivalence() {
        var a=new ReviewFinding("QUOTE_SCOPE","WARNING","范围不同","需核对","核对",List.of(),"MODEL");
        var b=new ReviewFinding("QUOTE_SCOPE","WARNING","范围不同","需核对","核对",List.of(),"MODEL");
        assertThat(CaseFindingPresentation.representatives(Map.of("AGENT_PRECHECK",review(a),"TENDER_COMPLIANCE",review(b)))).hasSize(2);
    }
    private SourceEvidence source(String text){return new SourceEvidence("e",UUID.randomUUID(),"招标.pdf","a".repeat(64),"page:1",text);}
    private ReviewFinding finding(String title,String code,String level,SourceEvidence source){return new ReviewFinding(code,level,title,title+"需关注","核对",List.of(source),"MODEL");}
    private ModuleReview review(ReviewFinding...findings){return new ModuleReview("PARTIAL","d","v9","m",Instant.EPOCH,Instant.EPOCH,List.of(),List.of(),List.of(),List.of(findings),0,null);}
    @Test void duplicateModuleOpinionsCollapseWithoutMergingDifferentRestrictionsOrSources() {
        var s=source("仅允许某品牌，同时仅允许本地注册企业参加。");
        var brand=finding("招标文件指定单一品牌","RESTRICTIVE_CONDITION","WARNING",s);
        var duplicate=finding("招标文件限定单一品牌","RESTRICTIVE_CONDITION","CRITICAL",s);
        var region=finding("招标文件设置地域注册门槛","RESTRICTIVE_CONDITION","WARNING",s);
        var otherSource=finding("招标文件限定单一品牌","RESTRICTIVE_CONDITION","WARNING",source("另一条独立品牌限制"));
        var chosen=CaseFindingPresentation.representatives(Map.of("AGENT_PRECHECK",review(brand,region),"TENDER_COMPLIANCE",review(duplicate,otherSource)));
        assertThat(chosen).containsExactlyInAnyOrder(duplicate,region,otherSource);
        assertThat(CaseFindingAcknowledgments.identity(duplicate)).isEqualTo(CaseFindingAcknowledgments.identity(chosen.stream().filter(f->f==duplicate).findFirst().orElseThrow()));
    }
    @Test void scopeSynonymsCollapseOnlyForIdenticalIssueTitleAndSources() {
        var s=source("甲供应商仅承诺12个月；乙供应商不包含耗材");
        var a=finding("甲报价范围与采购需求不符","QUOTE_SCOPE","WARNING",s);
        var b=finding("甲报价范围与采购要求不一致","QUOTE_SCOPE","WARNING",s);
        var other=finding("乙报价范围与采购需求不符","QUOTE_SCOPE","WARNING",s);
        assertThat(CaseFindingPresentation.representatives(Map.of("AGENT_PRECHECK",review(a,other),"TENDER_COMPLIANCE",review(b)))).hasSize(2).contains(other,b);
    }
}
