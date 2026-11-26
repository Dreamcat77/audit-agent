package com.fuquan.audit.contract.application.analysis;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractVisualQualityTest {
    @Test void uncertainAmountIsNeitherAConfirmedDefectNorAPassAndOtherEvidenceSurvives(){
        String text="合同金额人民币10000元\n签订日期2026年11月9日";
        var scope=ContractAnalysisEvidenceTest.scope(text);var e=new ContractAnalysisEvidence();
        var amount=e.locate(scope,"合同金额人民币10000元").orElseThrow();var date=e.locate(scope,"签订日期2026年11月9日").orElseThrow();
        var store=mock(VisualIndexStore.class);when(store.find(anyString(),anyString(),anyString())).thenReturn(Optional.of(new VisualIndex(scope.source().fileId().toString(),scope.source().sha256(),scope.source().textSha256(),List.of(
            new Anchor(1,"OCR",amount.start(),amount.end(),List.of(new Point(.1,.1),new Point(.8,.1),new Point(.8,.2),new Point(.1,.2)),.7,"UNCERTAIN")))));
        var quality=new ContractVisualQuality(new MaterialVisualEvidence(store));
        var bad=new Finding("AMOUNT","WARNING","金额大小写不一致","差异","核对",List.of(amount),"AI_REVIEW_SUGGESTION");
        var good=new Finding("DATE","WARNING","签订日期问题","日期","核对",List.of(date),"AI_REVIEW_SUGGESTION");
        var out=new Output("CONTRACT","GENERAL",true,List.of(new Fact("TOTAL_AMOUNT","10000","MODEL",List.of(amount))),List.of(),List.of(new Check("AMOUNT","FAIL","差异",List.of(amount))),List.of(bad,good),0,"test","v1");
        var projected=quality.project(out,scope.source(),text);
        assertThat(projected.facts()).isEmpty();assertThat(projected.checks().get(0).status()).isEqualTo("NOT_EVALUATED");
        assertThat(projected.findings().get(0).level()).isEqualTo("INFO");assertThat(projected.findings().get(1)).isEqualTo(good);
        assertThat(out.findings().get(0).level()).isEqualTo("WARNING");
    }
}
