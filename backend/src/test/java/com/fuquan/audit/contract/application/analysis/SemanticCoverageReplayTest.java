package com.fuquan.audit.contract.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Frozen synthetic provider responses: fix generation, never weaken evidence. */
class SemanticCoverageReplayTest {
    @Test void oldProviderErrorsRemainRejectedAndIndependentlyCorrectedCitationsPass() throws Exception {
        var json=new ObjectMapper();var validator=new ContractAnalysisEvidence();
        for(int index=0;index<3;index++) {
            var fixture=json.readTree(getClass().getResourceAsStream("/qa/semantic-coverage/file-"+index+".json"));
            var document=fixture.get("document");String text=document.get("text").asText();
            var source=new Source(UUID.fromString(document.get("id").asText()),document.get("name").asText(),"a".repeat(64),100,
                    SourceState.READY,UUID.randomUUID(),1,sha256(text),null);
            var scope=new ContractAnalysisEvidence.Scope(source,text,0,text.length());
            var raw=json.treeToValue(fixture.get("result"),ContractAnalysisAiPort.Result.class);
            var rejected=validator.validate(raw,List.of(scope),"FILE");
            assertThat(rejected.complete()).as("old response %s",index).isFalse();
            assertThat(rejected.clauses()).isNotEmpty();
            var corrected=new ArrayList<ContractAnalysisAiPort.Fact>();
            for(var fact:raw.facts()) {
                if(fact.type().equals("SIGN_DATE")||fact.type().equals("CONTRACT_REFERENCE")||fact.type().equals("TOTAL_AMOUNT"))continue;
                if(fact.type().equals("AWARD_SUPPLIER")) {
                    // Include both the actual named addressee and deictic award
                    // sentence, copied literally from this same frozen source.
                    fact=new ContractAnalysisAiPort.Fact(fact.type(),fact.value(),List.of(new ContractAnalysisAiPort.Citation(source.fileId(),text)),fact.subjectScope());
                }
                corrected.add(fact);
            }
            var valid=new ContractAnalysisAiPort.Result(raw.model(),raw.version(),raw.role(),raw.profile(),true,corrected,raw.clauses(),raw.findings());
            var accepted=validator.validate(valid,List.of(scope),"FILE");
            assertThat(accepted.complete()).as("corrected response %s",index).isTrue();
            assertThat(accepted.rejectedCitations()).isZero();
            assertThat(accepted.clauses()).hasSameSizeAs(raw.clauses());
        }
    }
}
