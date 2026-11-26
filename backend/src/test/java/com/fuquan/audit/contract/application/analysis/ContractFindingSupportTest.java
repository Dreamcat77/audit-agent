package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractFindingSupportTest {
    final ContractAnalysisEvidence ground=new ContractAnalysisEvidence();
    @Test void priceDifferenceRetainsItsEvidenceWithoutInventingContractInvalidity(){
        String main="合同含税总价：480000元。",quote="投标报价金额：470000元。";
        var contract=source(main);var bid=source(quote);
        var a=ground.locate(new ContractAnalysisEvidence.Scope(contract,main,0,main.length()),main).orElseThrow();
        var b=ground.locate(new ContractAnalysisEvidence.Scope(bid,quote,0,quote.length()),quote).orElseThrow();
        var original=new Finding("CROSS_FILE_CONFLICT","WARNING","合同金额与投标报价不一致",
                "合同价480000元，投标报价470000元，两者存在差异。该差异可能影响合同效力或履约基础，需进一步核实。",
                "核对金额口径及差异原因。",List.of(a,b),"AI_REVIEW_SUGGESTION");
        var narrowed=ContractFindingSupport.narrow(original,contract,main);
        assertThat(narrowed.explanation()).contains("480000元","470000元","存在差异").doesNotContain("影响合同效力","履约基础");
        assertThat(narrowed.evidence()).isEqualTo(original.evidence());assertThat(narrowed.code()).isEqualTo(original.code());
        assertThat(original.explanation()).contains("影响合同效力");
        assertThat(ContractFindingSupport.narrow(narrowed,contract,main)).isEqualTo(narrowed);
        var citations=List.of(new ContractAnalysisAiPort.Citation(contract.fileId(),main),new ContractAnalysisAiPort.Citation(bid.fileId(),quote));
        var candidate=new ContractAnalysisAiPort.Finding(original.code(),original.level(),original.title(),original.explanation(),original.suggestion(),citations);
        var admitted=ground.validate(ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of(candidate)),
                List.of(new ContractAnalysisEvidence.Scope(contract,main,0,main.length()),new ContractAnalysisEvidence.Scope(bid,quote,0,quote.length())),"CROSS_FILE");
        assertThat(admitted.findings()).containsExactly(narrowed);assertThat(admitted.complete()).isTrue();
    }
    @Test void expressValidityConditionAndUnrelatedDisputeRisksRemainIntact(){
        String text="合同金额高于中标价时本合同不生效，须另行审批。";var s=source(text);
        String other="中标金额：470000元。";var otherSource=source(other);
        var evidence=List.of(ground.locate(new ContractAnalysisEvidence.Scope(s,text,0,text.length()),text).orElseThrow(),
                ground.locate(new ContractAnalysisEvidence.Scope(otherSource,other,0,other.length()),other).orElseThrow());
        var explicit=new Finding("CROSS_FILE_CONFLICT","WARNING","合同金额差异影响生效条件","材料明确约定高于中标价时本合同不生效，需要核对审批","核对生效条件",evidence,"AI_REVIEW_SUGGESTION");
        assertThat(ContractFindingSupport.narrow(explicit,s,text)).isEqualTo(explicit);
        var dispute=finding(s,text,text,"OTHER_CONCRETE_RISK","争议救济条款需核对","过度限制法定救济可能影响条款效力");
        assertThat(ContractFindingSupport.narrow(dispute,s,text)).isEqualTo(dispute);
    }
    @Test void actorReversalIsRejectedOnAdmissionAndOnHistoricalProjection(){
        String text="乙方不得向法院提起诉讼，且乙方须承担全部赔偿责任。";var s=source(text);
        var bad=finding(s,text,text,"OTHER_CONCRETE_RISK","甲方追责受限","导致甲方无法通过法定程序追责");
        var valid=finding(s,text,text,"OTHER_CONCRETE_RISK","条款可执行性","过度限制救济可能影响条款可执行性，需核对适用依据");
        var output=new Output("CONTRACT","GENERAL",true,List.of(),List.of(new Clause("DISPUTE","甲方无法起诉",bad.evidence())),List.of(),List.of(bad,valid),0,"test","contract-case-v1");
        var projected=ContractAnalysisSemantics.project(output,s,text,false);
        assertThat(projected.findings()).containsExactly(valid);assertThat(projected.clauses()).isEmpty();
        assertThat(output.findings()).containsExactly(bad,valid);
        var candidate=new ContractAnalysisAiPort.Finding(bad.code(),bad.level(),bad.title(),bad.explanation(),bad.suggestion(),List.of(new ContractAnalysisAiPort.Citation(s.fileId(),text)));
        var result=ground.validate(ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of(candidate)),List.of(new ContractAnalysisEvidence.Scope(s,text,0,text.length())),"FILE");
        assertThat(result.findings()).isEmpty();assertThat(result.complete()).isFalse();
    }
    Source source(String text){return ContractAnalysisEvidenceTest.source(text);}
    Finding finding(Source s,String text,String quote,String code,String title,String description){
        var e=ground.locate(new ContractAnalysisEvidence.Scope(s,text,0,text.length()),quote).orElseThrow();
        return new Finding(code,"INFO",title,description,"核对约定",List.of(e),"AI_REVIEW_SUGGESTION");
    }
    @Test void truncatedDateAtFragmentEndCannotBecomeAnIncompleteDateRiskEvenOnOldReports(){
        for(String ending:List.of("日", " 日", "\u2028日", "")){
            String text="签署日期：2026年10月22"+ending+"\n乙方（盖章）：青禾公司";var source=source(text);
            var f=finding(source,text,"签署日期：2026年10月22","MATERIAL_SCOPE_NOTE","签署日期不完整","缺少具体日数");
            assertThat(ContractFindingSupport.supports(f,source,text)).isFalse();
            var out=new Output("CONTRACT","GENERAL",true,List.of(),List.of(),List.of(),List.of(f),0,"test","contract-case-v1");
            assertThat(ContractAnalysisSemantics.project(out,source,text,false).findings()).isEmpty();
            assertThat(out.findings()).containsExactly(f);
        }
    }
    @Test void invalidAndUnfilledDatesRemainActualIssues(){
        for(String value:List.of("2026年2月30日","____年____月____日")){
            String text="签署日期："+value;var s=source(text);
            assertThat(ContractFindingSupport.supports(finding(s,text,text,"MATERIAL_SCOPE_NOTE","签署日期不完整","日期不完整或无效"),s,text)).isTrue();
        }
    }
    @Test void titleCannotSupportTerminationClaimAndOldDeterministicFalseClaimIsReplacedByRealEndCondition(){
        String text="合同编号：SYN-44\n本协议一式四份，经双方授权代表签字盖章后生效。协议有效期自生效之日起至本协议执行完毕止。";var s=source(text);
        var f=finding(s,text,"合同编号：SYN-44","TERMINATION_COMPLETENESS","合同日期与终止条件需要关注","合同未列明终止日期或终止条件");
        assertThat(ContractFindingSupport.supports(f,s,text)).isFalse();
        var check=new Check(f.code(),"FAIL",f.explanation(),f.evidence());
        var out=new Output("OTHER","GENERAL",true,List.of(),List.of(),List.of(check),List.of(f),0,null,"document-rules-v1");
        var projected=ContractAnalysisSemantics.project(out,s,text,false);
        assertThat(projected.findings()).isEmpty();
        assertThat(projected.checks()).anySatisfy(c->{assertThat(c.code()).isEqualTo("TERMINATION_COMPLETENESS");assertThat(c.status()).isEqualTo("PASS");assertThat(c.evidence()).anyMatch(e->e.excerpt().contains("执行完毕止"));});
        assertThat(out.findings()).containsExactly(f);
    }
    @Test void modelCitationMustSupportDateIssueRatherThanOnlyExistLiterally(){
        String text="合同编号：SYN-44\n有效期至执行完毕止";var s=source(text);
        var cite=new ContractAnalysisAiPort.Citation(s.fileId(),"合同编号：SYN-44");
        var f=new ContractAnalysisAiPort.Finding("TERMINATION_AMBIGUITY","WARNING","终止条件不明确","合同没有终止条件","补全期限",List.of(cite));
        assertThat(ground.validate(ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of(f)),List.of(new ContractAnalysisEvidence.Scope(s,text,0,text.length())),"FILE").findings()).isEmpty();
    }
    @Test void fullSourceRefutesAbsenceButDoesNotDismissAConcreteTerminationDispute(){
        String quote="合同有效期至执行完毕止。";
        String text="合同编号：SYN-44\n"+quote;var s=source(text);
        assertThat(ContractFindingSupport.supports(finding(s,text,quote,"TERMINATION_AMBIGUITY","缺少终止条件","合同未列明终止日期或终止条件"),s,text)).isFalse();
        assertThat(ContractFindingSupport.supports(finding(s,text,quote,"TERMINATION_AMBIGUITY","终止条件需明确","执行完毕未明确以何种交付验收标准判断"),s,text)).isTrue();
    }
    @Test void completeDateElsewhereMustNotHideTheActuallyBlankSigningDate(){
        String blank="签署日期：____年____月____日";
        String text="签署日期：2026年10月22日\n甲方盖章\n"+blank+"\n乙方盖章";var s=source(text);
        assertThat(ContractFindingSupport.supports(finding(s,text,blank,"MATERIAL_SCOPE_NOTE","签署日期不完整","乙方签署日期未填写"),s,text)).isTrue();
    }
    @Test void newRangesAvoidCuttingDateAndRemainCoveredWithoutChangingFrozenWork(){
        String date="签署日期：2026年10月22日";
        String text="甲".repeat(2383)+"。\n"+date+"\n"+"乙".repeat(120);
        int end=ContractAnalysisPlan.fragmentEnd(text,0);
        assertThat(end).isLessThanOrEqualTo(2400).isGreaterThanOrEqualTo(2240);
        assertThat(text.substring(0,end)).doesNotContain("签署日期");
        assertThat(text.substring(2240)).contains(date);
        var s=source(text);var frozen=ContractAnalysisPlan.step("analyze-v2/"+s.fileId()+"/0",s,"FRAGMENT",0,2400);
        var now=java.time.Instant.now();
        var c=new ContractAnalysisCase(UUID.randomUUID(),UUID.randomUUID(),"test","a".repeat(64),State.QUEUED,0,null,null,0,now,now.plusSeconds(900),List.of(s),List.of(frozen),null);
        var plan=new ContractAnalysisPlan();var work=plan.prepare(c,Map.of(s.fileId(),text));
        assertThat(work).contains(frozen);
        assertThat(work.stream().filter(step->step.fileId().equals(s.fileId())&&step.kind().equals("FRAGMENT")).map(step->text.substring(step.start(),step.end()))).anyMatch(part->part.contains(date));
    }
}
