package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisRulesTest {
    final ContractAnalysisRules rules=new ContractAnalysisRules(new ProjectFactExtractor(Clock.systemUTC()),new ContractAnalysisEvidence());
    static Fact fact(Source s,String text,String type,String value){return new Fact(type,value,"SYNTHETIC_TEST",List.of(new Evidence(s.fileId(),s.sha256(),s.parseResultId(),1,s.textSha256(),0,text.length(),text)));}
    static Output output(List<Fact> facts){return new Output("CONTRACT","PROCUREMENT",true,facts,List.of(),List.of(),List.of(),0,"synthetic", "test-v1");}
    @Test void chineseUppercaseConflictUsesExactMoney(){
        String text="合同总价：10000元，金额大写：贰万元整";var s=ContractAnalysisEvidenceTest.source(text);
        var out=rules.evaluate(s,List.of(output(List.of(fact(s,text,"TOTAL_AMOUNT","10000元"),fact(s,text,"AMOUNT_WORDS","贰万元整")))),text);
        assertThat(out.checks()).anyMatch(c->c.code().equals(F02RuleCodes.AMOUNT_WORDS_MATCH)&&c.status().equals("FAIL"));
        assertThat(out.findings()).singleElement().satisfies(f->assertThat(f.explanation()).contains("10000"));
    }
    @Test void equalMoneyWithDifferentScalePasses(){
        String text="10000.00元，壹万元整";var s=ContractAnalysisEvidenceTest.source(text);
        var out=rules.evaluate(s,List.of(output(List.of(fact(s,text,"TOTAL_AMOUNT","10000.00元"),fact(s,text,"AMOUNT_WORDS","壹万元整")))),text);
        assertThat(out.checks()).anyMatch(c->c.code().equals(F02RuleCodes.AMOUNT_WORDS_MATCH)&&c.status().equals("PASS"));assertThat(out.findings()).isEmpty();
    }
    @Test void noMoneyDoesNotFabricatePassingChecks(){
        String text="验收合格后支付，未列明合同总价";var s=ContractAnalysisEvidenceTest.source(text);var out=rules.evaluate(s,List.of(),text);
        assertThat(out.checks()).allMatch(c->c.status().equals("NOT_EVALUATED"));assertThat(out.useful()).isFalse();
    }
    @Test void explicitContractNameBeatsCoverHeadingAndDisclaimer(){
        String text="无编号无金额服务合同\n合成测试数据 仅用于内审系统功能验收 不是真实业务合同\n合同名称：澄川档案整理服务合同\n甲方：测试甲方\n乙方：测试乙方";
        var s=ContractAnalysisEvidenceTest.source(text);
        var f=new com.fuquan.audit.contract.domain.project.ProjectFile(s.fileId(),UUID.randomUUID(),null,"样本.docx","样本.docx","application/octet-stream",com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY,
            com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus.AVAILABLE,com.fuquan.audit.contract.domain.project.ProjectFileParseStatus.PARSED,s.sha256(),s.sha256(),100,text,null,Instant.now(),Instant.now());
        assertThat(rules.recall(f,s,text).facts().stream().filter(v->v.type().equals("CONTRACT_NAME"))).extracting(Fact::value).containsExactly("澄川档案整理服务合同");
    }
    @Test void referencedParentNumberDoesNotBecomeTheSupplementOwnNumber(){
        String text="补充协议\n关联合同编号：MAIN-2026-01\n合同编号：SUP-2026-02\n甲方：测试甲方\n乙方：测试乙方";
        var s=ContractAnalysisEvidenceTest.source(text);
        var file=new com.fuquan.audit.contract.domain.project.ProjectFile(s.fileId(),UUID.randomUUID(),null,"补充协议.docx","补充协议.docx","application/octet-stream",com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY,
            com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus.AVAILABLE,com.fuquan.audit.contract.domain.project.ProjectFileParseStatus.PARSED,s.sha256(),s.sha256(),100,text,null,Instant.now(),Instant.now());
        var facts=rules.recall(file,s,text).facts();
        assertThat(facts.stream().filter(f->f.type().equals("CONTRACT_REFERENCE"))).extracting(Fact::value).containsExactly("MAIN-2026-01");
        assertThat(facts.stream().filter(f->f.type().equals("CONTRACT_NO"))).extracting(Fact::value).containsExactly("SUP-2026-02");
    }
    @Test void intactPhysicalRowsRemainDistinctEvenWithSameName(){
        String text="合同价款明细\n名称\t数量\t单价\t金额\n相同名称\t2\t10\t20\n相同名称\t3\t10\t30\n合计\t\t\t50\n";
        var s=ContractAnalysisEvidenceTest.source(text);var out=rules.evaluate(s,List.of(output(List.of(fact(s,text,"TOTAL_AMOUNT","50")))),text);
        assertThat(out.checks().stream().filter(c->c.code().equals(F02RuleCodes.LINE_ITEM_ARITHMETIC))).hasSize(2).allMatch(c->c.status().equals("PASS"));
        assertThat(out.checks()).anyMatch(c->c.code().equals(F02RuleCodes.LINE_SUM_MATCH)&&c.status().equals("PASS"));
    }
    @Test void incompleteTableNeverClaimsWholeContractSumMismatch(){
        String text="名称\t数量\t单价\t金额\n终端\t2\t10\t20\n其他未完整行";var s=ContractAnalysisEvidenceTest.source(text);
        var out=rules.evaluate(s,List.of(output(List.of(fact(s,text,"TOTAL_AMOUNT","100")))),text);
        assertThat(out.checks()).anyMatch(c->c.code().equals(F02RuleCodes.LINE_SUM_MATCH)&&c.status().equals("NOT_EVALUATED"));
    }
    @Test void differingCandidateAmountsAreNotSilentlyChosen(){
        String text="合同总价：100元；合同总价：200元";var s=ContractAnalysisEvidenceTest.source(text);
        assertThat(ContractAnalysisRules.unique(List.of(fact(s,text,"TOTAL_AMOUNT","100元"),fact(s,text,"TOTAL_AMOUNT","200元")),"TOTAL_AMOUNT")).isEmpty();
    }
    @Test void unitConversionAndUppercaseAmountsAreNotFloats(){
        assertThat(ContractAnalysisRules.money("人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分").orElseThrow()).isEqualByComparingTo("123456.78");
        assertThat(ContractAnalysisRules.money("12.3456万元").orElseThrow()).isEqualByComparingTo("123456");
        assertThat(ContractAnalysisRules.money("50%")).isEmpty();assertThat(ContractAnalysisRules.money("约1至2万元")).isEmpty();
    }
    @Test void modelCoverageCannotUseRulesOnlyAsSemanticCoverage(){
        var s=ContractAnalysisEvidenceTest.source("a".repeat(12000));var c=ContractAnalysisCaseTest.sample();
        var ready=new ContractAnalysisCase(c.id(),c.projectId(),c.owner(),c.inputDigest(),c.status(),0,null,null,0,c.createdAt(),c.deadline(),List.of(s),List.of(),null);
        var plan=new ContractAnalysisPlan();var steps=plan.prepare(ready,Map.of(s.fileId(),"a".repeat(12000)));
        assertThat(steps.stream().filter(t->t.kind().equals("FRAGMENT"))).hasSize(6);
        assertThat(plan.fullCoverage(s,steps,12000)).isFalse();
    }
}
