package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnnexProviderReplayTest {
    @Test void listedAnnexAmountsAreAnObservationRatherThanAPassedContractComparison() throws Exception {
        var fixture=new ObjectMapper().readTree(getClass().getResourceAsStream("/quality/c19-annex-provider.json"));
        String text=fixture.path("text").asText();
        var source=new Source(UUID.randomUUID(),"附件一合同价款明细表.xlsx","a".repeat(64),100,
                SourceState.READY,UUID.randomUUID(),1,sha256(text),null);
        var rules=new ContractAnalysisRules(new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(java.time.Clock.systemUTC()),new ContractAnalysisEvidence());
        var out=rules.evaluate(source,List.of(),text);
        assertThat(out.complete()).isTrue();assertThat(out.useful()).isTrue();assertThat(out.findings()).isEmpty();
        assertThat(out.checks()).singleElement().satisfies(c->{
            assertThat(c.status()).isEqualTo("SOURCE_GROUNDED_OBSERVATION");
            assertThat(c.explanation()).contains("2 项","450000.00").doesNotContain("核对一致","通过");
            assertThat(c.evidence()).hasSize(2);
        });
        assertThat(ContractAnalysisCategories.project(List.of(),out.checks(),List.of()).get(0).items())
                .singleElement().satisfies(i->assertThat(i.title()).isEqualTo("金额明细"));
        assertThatThrownBy(()->new Check("F02_AMOUNT_WORDS_MATCH","SOURCE_GROUNDED_OBSERVATION","unproved",List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aGroundedAnnexResponseRetainsIndependentFactsEvenIfOneAmountIsRejected() throws Exception {
        var mapper=new ObjectMapper();
        var fixture=mapper.readTree(getClass().getResourceAsStream("/quality/c19-annex-provider.json"));
        String text=fixture.path("text").asText();
        var source=new Source(UUID.fromString(fixture.path("fileId").asText()),"附件一合同价款明细表.xlsx","a".repeat(64),100,
                SourceState.READY,UUID.randomUUID(),1,sha256(text),null);
        var result=mapper.treeToValue(fixture.path("result"),ContractAnalysisAiPort.Result.class);
        var scope=new ContractAnalysisEvidence.Scope(source,text,0,text.length());
        var output=new ContractAnalysisEvidence().validate(result,List.of(scope),"FILE");
        assertThat(output.facts()).extracting(Fact::type).contains("PROJECT_NAME","CONTRACT_NO","CURRENCY","LINE_TABLE_COMPLETE");
        assertThat(output.clauses()).hasSize(1);
    }
}
