package com.fuquan.audit.contract.application.reviewrun.semantic;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticHierarchyV2ManifestTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode manifest() {
        var m=json.createObjectNode().put("timeLimitMs",900000).put("unitTimeLimitMs",300000)
                .put("repairLimit",2).put("callLimit",5).put("maxOutputTokensPerCall",6144).put("maxTotalOutputTokens",30720);
        m.putObject("policy").put("version","contract-semantic-work-v3").put("hierarchyProtocol","contract-bounded-hierarchy-v2")
                .put("monetaryMode","OBSERVE").put("reasoningEffort","none").put("transport","PROMPT_ONLY")
                .put("maxOutputTokens",6144).put("automaticTransportRetries",0).put("hierarchyGroupInputMaxChars",12000)
                .put("hierarchyProviderDraftProtocol","contract-hierarchy-provider-references-v2")
                .put("hierarchyFinalInputMaxChars",48000).put("hierarchyFinalInputMaxBytes",144000)
                .put("finalEvidenceProtocol","contract-final-frozen-evidence-v1").put("finalEvidenceEncoding","literal-lines-v1");
        var h=m.putObject("hierarchy").put("version",2).put("membershipStrategy","scope-file-size-v1")
                .put("maximumGroups",3).put("leafIdentity","analysis-logical-key+hint-ordinal").put("decisionAfter","ALL_ANALYSES_VERIFIED")
                .put("groupInputMaxChars",12000).put("finalInputMaxChars",48000).put("finalInputMaxBytes",144000)
                .put("finalEvidenceProtocol","contract-final-frozen-evidence-v1").put("finalLogicalKey","c".repeat(64));
        h.putArray("groupLogicalKeys").add("b".repeat(64));
        var units=m.putArray("units");
        for(int i=0;i<3;i++) {
            var u=units.addObject().put("logicalKey",String.valueOf((char)('a'+i)).repeat(64)).put("inputFingerprint","d".repeat(64))
                    .put("ordinal",i+1).put("role",List.of("ANALYSIS","GROUP","FINAL").get(i)).put("purpose",i==0?"ANALYZE":"SYNTHESIZE");
            var dependencies=u.putArray("dependencies");if(i>0)dependencies.add("a".repeat(64));if(i==2)dependencies.add("b".repeat(64));
        }
        return m;
    }
    @Test void v2FrozenBoundsAreAcceptedButCannotBeReadAsV1() {
        assertThat(SemanticWorkManifest.finalLogicalKey(manifest(),"contract-bounded-hierarchy-v2")).isEqualTo("c".repeat(64));
        assertThatThrownBy(()->SemanticWorkManifest.finalLogicalKey(manifest(),"contract-bounded-hierarchy-v1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void businessAdapterHasItsActualWireProtocolAndIndependentDerivedLimits() {
        var m=manifest();var p=(ObjectNode)m.path("policy");
        p.put("businessAdapterProtocol","contract-semantic-business-issues-v1")
                .put("hierarchyProviderDraftProtocol","contract-semantic-business-issues-v1")
                .put("serverDerivedMaxIds",6400).put("serverDerivedMaxBytes",131072)
                .put("businessRegenerationProtocol","contract-semantic-regeneration-business-v1").put("businessRegenerationMaxBytes",24576);
        assertThat(SemanticWorkManifest.finalLogicalKey(m,"contract-bounded-hierarchy-v2")).isEqualTo("c".repeat(64));
        p.put("serverDerivedMaxIds",6401);
        assertThatThrownBy(()->SemanticWorkManifest.finalLogicalKey(m,"contract-bounded-hierarchy-v2")).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"version","chars","bytes","evidence","policyBytes","policyChars","policyEvidence","provider","group","policyGroup","time","unitTime","repairs","calls","tokens","retry","boolBytes","encodingMissing","encodingWrong","encodingBoolean"})
    void wrongOrMixedV2ContractsAreRejectedBeforeExecution(String defect) {
        var m=manifest();var h=(ObjectNode)m.path("hierarchy");var p=(ObjectNode)m.path("policy");
        switch(defect) {
            case "version"->h.put("version",1);case "chars"->h.put("finalInputMaxChars",12000);
            case "bytes"->h.remove("finalInputMaxBytes");case "evidence"->h.put("finalEvidenceProtocol","untrusted");
            case "policyBytes"->p.put("hierarchyFinalInputMaxBytes",144001);case "policyChars"->p.put("hierarchyFinalInputMaxChars",48001);
            case "policyEvidence"->p.remove("finalEvidenceProtocol");case "provider"->p.put("hierarchyProviderDraftProtocol","contract-hierarchy-provider-references-v1");
            case "group"->h.put("groupInputMaxChars",48000);case "policyGroup"->p.put("hierarchyGroupInputMaxChars",48000);
            case "time"->m.put("timeLimitMs",899999);case "unitTime"->m.put("unitTimeLimitMs",299999);
            case "repairs"->m.put("repairLimit",3);case "calls"->m.put("callLimit",6);case "tokens"->m.put("maxOutputTokensPerCall",8192);
            case "retry"->p.put("automaticTransportRetries",1);case "boolBytes"->h.put("finalInputMaxBytes",true);
            case "encodingMissing"->p.remove("finalEvidenceEncoding");case "encodingWrong"->p.put("finalEvidenceEncoding","unknown");
            case "encodingBoolean"->p.put("finalEvidenceEncoding",true);
        }
        assertThatThrownBy(()->SemanticWorkManifest.finalLogicalKey(m,"contract-bounded-hierarchy-v2")).isInstanceOf(IllegalArgumentException.class);
    }
}
