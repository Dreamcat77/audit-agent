package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisRelationsTest {
    final ContractAnalysisRelations relations=new ContractAnalysisRelations();
    final Source a=source("合同编号：A，项目编号：P，合同总价：100元，乙方：合成商甲");
    final Source b=source("项目编号：P，中标金额：90元，中标供应商：合成商乙");
    static Source source(String text){var s=ContractAnalysisEvidenceTest.source(text);return new Source(s.fileId(),"材料.docx",s.sha256(),s.size(),s.status(),s.parseResultId(),s.parseVersion(),s.textSha256(),null);}
    @Test void currentAnalysisUsesFilenameBeforeLocalModelFragmentRoleWithoutInventingFacts(){
        var neutral=source("验收条款");
        var named=new Source(neutral.fileId(),"合同书.pdf",neutral.sha256(),neutral.size(),neutral.status(),neutral.parseResultId(),1,neutral.textSha256(),null);
        assertThat(relations.role(named,List.of(step(named,"OTHER",List.of())))).isEqualTo("CONTRACT");
        assertThat(relations.pairs(List.of(named,b),List.of(step(named,"OTHER",List.of()),step(b,"AWARD",List.of())))).isEmpty();
    }
    Fact f(Source source,String type,String value){return ContractAnalysisRulesTest.fact(source,"合成引用",type,value);}
    Step step(Source source,String role,List<Fact> facts){return ContractAnalysisPlan.step("model/"+source.fileId(),source,"FILE",0,4)
        .running(ContractAnalysisCaseTest.NOW).finish(StepState.COMPLETED,new Output(role,"GENERAL",true,facts,List.of(),List.of(),List.of(),0,"test","v1"),null,ContractAnalysisCaseTest.NOW);}
    @Test void uniqueExplicitProjectReferenceAllowsBothAwardChecks(){
        var work=List.of(step(a,"CONTRACT",List.of(f(a,"PROJECT_NO","P"),f(a,"TOTAL_AMOUNT","100元"),f(a,"PARTY_B","合成商甲"))),
            step(b,"AWARD",List.of(f(b,"PROJECT_NO","P"),f(b,"AWARD_AMOUNT","90元"),f(b,"AWARD_SUPPLIER","合成商乙"))));
        var pair=relations.pairs(List.of(a,b),work).get(0);var output=relations.evaluate(pair,work);
        assertThat(output.findings()).hasSize(2).allMatch(v->v.evidence().stream().map(Evidence::fileId).distinct().count()==2);
    }
    @Test void twoContractsForSameProjectMustNotShareAnUnspecifiedAward(){
        var c=source("另一合同");
        var work=List.of(step(a,"CONTRACT",List.of(f(a,"PROJECT_NO","P"))),step(c,"CONTRACT",List.of(f(c,"PROJECT_NO","P"))),step(b,"AWARD",List.of(f(b,"PROJECT_NO","P"))));
        assertThat(relations.pairs(List.of(a,b,c),work)).isEmpty();
    }
    @Test void explicitContractReferenceMayDisambiguateMultipleContracts(){
        var c=source("另一合同");
        var work=List.of(step(a,"CONTRACT",List.of(f(a,"CONTRACT_NO","A"))),step(c,"CONTRACT",List.of(f(c,"CONTRACT_NO","C"))),step(b,"SUPPLEMENT",List.of(f(b,"CONTRACT_REFERENCE","A"))));
        var pairs=relations.pairs(List.of(a,b,c),work);assertThat(pairs).hasSize(1);assertThat(pairs.get(0).contract()).isEqualTo(a);
        assertThat(relations.evaluate(pairs.get(0),work).findings()).isEmpty();
    }
    @Test void batchMembershipAloneIsNotAContractRelationship(){
        assertThat(relations.pairs(List.of(a,b),List.of(step(a,"CONTRACT",List.of()),step(b,"AWARD",List.of())))).isEmpty();
    }
    @Test void conflictingExplicitReferenceCannotFallBackToTheSameProject(){
        var work=List.of(step(a,"CONTRACT",List.of(f(a,"CONTRACT_NO","A"),f(a,"PROJECT_NO","P"))),step(b,"SUPPLEMENT",List.of(f(b,"CONTRACT_REFERENCE","OTHER"),f(b,"PROJECT_NO","P"))));
        assertThat(relations.pairs(List.of(a,b),work)).isEmpty();
    }
    @Test void duplicateContractNumbersCannotAssociateSupportToBothContracts(){
        var c=source("另一份同号合同");
        var work=List.of(step(a,"CONTRACT",List.of(f(a,"CONTRACT_NO","A"))),step(c,"CONTRACT",List.of(f(c,"CONTRACT_NO","A"))),step(b,"SUPPLEMENT",List.of(f(b,"CONTRACT_REFERENCE","A"))));
        assertThat(relations.pairs(List.of(a,b,c),work)).isEmpty();
    }
    @Test void conflictingDocumentRolesAreNotResolvedByModelVoting(){
        var first=step(a,"CONTRACT",List.of());var second=new Step("second",a.fileId(),"FRAGMENT",5,9,sha256("second"),StepState.COMPLETED,ContractAnalysisCaseTest.NOW,ContractAnalysisCaseTest.NOW,new Output("AWARD","GENERAL",true,List.of(),List.of(),List.of(),List.of(),0,"test","v1"),null);
        assertThat(relations.role(a,List.of(first,second))).isEqualTo("OTHER");
    }
    @Test void crossFileInputKeepsConflictEvidenceThatHasNoClauseOrFact(){
        String main="合同编号：A\n验收后支付65%";
        String support="合成背景。".repeat(1100)+"\n另行支付40%，原合同付款仍足额执行且互不抵扣。";
        var c=source(main);var s=source(support);
        int at=support.indexOf("另行支付");
        var evidence=new Evidence(s.fileId(),s.sha256(),s.parseResultId(),1,s.textSha256(),at,support.length(),support.substring(at));
        var finding=new Finding("PAYMENT_CONFLICT","WARNING","重复付款","两套付款并行","核对条款",List.of(evidence),"AI_REVIEW_SUGGESTION");
        var step=ContractAnalysisPlan.step("model/"+s.fileId(),s,"FILE",0,support.length()).running(ContractAnalysisCaseTest.NOW)
            .finish(StepState.COMPLETED,new Output("SUPPLEMENT","GENERAL",true,List.of(),List.of(),List.of(),List.of(finding),0,"test","v1"),null,ContractAnalysisCaseTest.NOW);
        var scopes=relations.scopes(new ContractAnalysisRelations.Pair(c,s),List.of(step),Map.of(c.fileId(),main,s.fileId(),support));
        assertThat(scopes.stream().filter(v->v.source().fileId().equals(s.fileId()))).singleElement()
            .satisfies(v->assertThat(v.supplied()).isEqualTo(support.substring(at)));
        assertThat(scopes.stream().mapToInt(v->v.supplied().length()).sum()).isLessThanOrEqualTo(10000);
    }
    @Test void shortFilesAreSuppliedWholeRatherThanOnlySuccessfulExtractionSnippets(){
        String main="合同编号：A\n付款30%、65%、5%。";String support="关联合同编号：A\n新付款替换原约定，旧比例不再适用。";
        var c=source(main);var s=source(support);
        assertThat(relations.scopes(new ContractAnalysisRelations.Pair(c,s),List.of(),Map.of(c.fileId(),main,s.fileId(),support)))
            .extracting(ContractAnalysisEvidence.Scope::supplied).containsExactly(main,support);
    }
}
