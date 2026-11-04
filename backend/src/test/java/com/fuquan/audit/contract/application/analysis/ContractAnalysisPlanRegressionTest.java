package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisPlanRegressionTest {
    final ContractAnalysisPlan plan=new ContractAnalysisPlan();
    ContractAnalysisCase batch(List<Source> files,List<Step> work){
        var c=ContractAnalysisCaseTest.sample();
        return new ContractAnalysisCase(c.id(),c.projectId(),c.owner(),c.inputDigest(),State.QUEUED,0,null,null,0,
            c.createdAt(),c.deadline(),files,work,null);
    }
    @Test void customerSizedSevenFileBatchGetsEveryRangeAndReservedRecoveryCapacity(){
        int[] lengths={8242,33759,40121,8242,8237,1821,40121};
        var files=new ArrayList<Source>();var texts=new LinkedHashMap<UUID,String>();
        for(int length:lengths){String text="测".repeat(length);var s=ContractAnalysisEvidenceTest.source(text);files.add(s);texts.put(s.fileId(),text);}
        var c=batch(files,List.of());var work=plan.prepare(c,texts);
        var model=work.stream().filter(s->Set.of("FILE","FRAGMENT").contains(s.kind())).toList();
        assertThat(model).hasSizeGreaterThan(30);assertThat(model.size()).isLessThan(plan.callBudget(c,texts));
        assertThat(model).allMatch(s->s.end()-s.start()<=2400);
        var complete=work.stream().map(s->s.finish(StepState.COMPLETED,
            new Output("OTHER","GENERAL",true,List.of(),List.of(),List.of(),List.of(),0,"test","v1"),null,c.createdAt())).toList();
        for(var source:files)assertThat(plan.fullCoverage(source,complete,texts.get(source.fileId()).length())).isTrue();
        assertThat(plan.prepare(batch(files,work),texts)).isEqualTo(work);
    }
    @Test void existingUnknownOrFailedRangesNeverBecomeNewModelRequestsAfterUpgrade(){
        String text="测".repeat(5500);var source=ContractAnalysisEvidenceTest.source(text);
        for(var state:List.of(StepState.UNKNOWN,StepState.FAILED)){
            var old=ContractAnalysisPlan.step("analyze/old",source,"FILE",0,text.length()).finish(state,null,"MODEL_OUTCOME_UNKNOWN",ContractAnalysisCaseTest.NOW);
            var work=plan.prepare(batch(List.of(source),List.of(old)),Map.of(source.fileId(),text));
            assertThat(work.stream().filter(s->Set.of("FILE","FRAGMENT").contains(s.kind()))).containsExactly(old);
        }
    }
    @Test void extremeBatchesStayBoundedAndCannotClaimFullCoverage(){
        String text="测".repeat(1000000);var s=ContractAnalysisEvidenceTest.source(text);var c=batch(List.of(s),List.of());
        assertThat(plan.callBudget(c,Map.of(s.fileId(),text))).isEqualTo(256);
        var work=plan.prepare(c,Map.of(s.fileId(),text));
        assertThat(work.stream().filter(w->w.kind().equals("FRAGMENT")).count()).isEqualTo(256);
        assertThat(plan.fullCoverage(s,work,text.length())).isFalse();
    }
    @Test void validEmptySectionsCountAsReviewedButRejectedResultsDoNot(){
        String text="空白附件说明";var source=ContractAnalysisEvidenceTest.source(text);
        var empty=new Output("OTHER","GENERAL",true,List.of(),List.of(),List.of(),List.of(),0,"test","v1");
        var step=ContractAnalysisPlan.step("empty",source,"FILE",0,text.length()).finish(StepState.DATA_INSUFFICIENT,empty,null,ContractAnalysisCaseTest.NOW);
        assertThat(plan.fullCoverage(source,List.of(step),text.length())).isTrue();
        assertThat(ContractAnalysisReport.covered(List.of(step),text.length())).isEqualTo(text.length());
        var rejected=new Output("OTHER","GENERAL",false,List.of(),List.of(),List.of(),List.of(),1,"test","v1");
        var invalid=ContractAnalysisPlan.step("invalid",source,"FILE",0,text.length()).finish(StepState.DATA_INSUFFICIENT,rejected,null,ContractAnalysisCaseTest.NOW);
        assertThat(plan.fullCoverage(source,List.of(invalid),text.length())).isFalse();
    }
    @Test void outputLimitRecoveryNeverCutsSupplementaryCharacters(){
        String text="😀".repeat(1200);var source=ContractAnalysisEvidenceTest.source(text);
        var failed=ContractAnalysisPlan.step("old",source,"FILE",0,text.length()).finish(StepState.FAILED,null,"MODEL_OUTPUT_LIMIT",ContractAnalysisCaseTest.NOW);
        for(var step:plan.prepare(batch(List.of(source),List.of(failed)),Map.of(source.fileId(),text)))if(step.id().startsWith("split/")){
            assertThat(Character.isLowSurrogate(text.charAt(step.start()))).isFalse();
            assertThat(Character.isHighSurrogate(text.charAt(step.end()-1))).isFalse();
        }
    }
}
