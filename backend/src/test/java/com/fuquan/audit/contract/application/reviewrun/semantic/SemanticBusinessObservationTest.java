package com.fuquan.audit.contract.application.reviewrun.semantic;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticBusinessObservationTest {
    final ObjectMapper json=new ObjectMapper(); final UUID plan=UUID.randomUUID(),attempt=UUID.randomUUID();
    final String protocol="contract-semantic-business-issues-v1";
    final ObjectNode hint=json.createObjectNode().put("category","DELIVERY_TERMS").put("attention","INFO")
            .put("title","交付约定").put("description","乙方依合同完成交付。").put("claimKind","CLAUSE_PRESENT");
    final ObjectNode scope=json.createObjectNode().put("topic","DELIVERY").put("responsibleParty","PARTY_B").put("trigger","交付");
    final ObjectNode group=json.createObjectNode().put("role","GROUP").put("status","VERIFIED");
    final ObjectNode packet=json.createObjectNode().put("role","FINAL").put("status","VERIFIED")
            .put("groupingFingerprint","a".repeat(64)).put("operationFingerprint","d".repeat(64));
    SemanticBusinessObservationTest(){
        hint.set("obligationScope",scope);hint.putArray("problemEvidenceIds");hint.putArray("comparisonEvidenceIds").add("C:S1");hint.putNull("unavailableMaterialCategory");
        group.putObject("validatedResult").putArray("hints").add(hint);
        var digest=group.putObject("quality").putObject("groupDigest");var row=digest.putArray("claims").addArray();
        row.add(3).add(0).add(hint.path("title").asText()).add(hint.path("description").asText()).add(0).add(0);
        row.addArray();row.addArray().add(0);row.addNull();
        var quality=packet.putObject("quality");quality.putArray("originalObligationScopes").add(scope);
        quality.putObject("finalEvidenceProof").putArray("evidenceRegistry").add("C:S1");
        var identity=json.createObjectNode().put("role","FINAL").put("grouping","a".repeat(64)).put("alias","G1:H1");identity.set("original",hint);
        String id="I"+SemanticMonetaryObservationVerifier.canonicalHash(identity).substring(0,32);
        quality.putObject("businessAdapter").put("protocol",protocol).putArray("issueIds").add(id);
        var raw=packet.putObject("completion").putObject("payload").put("protocol",protocol).put("summary","综合核查。");
        raw.putArray("decisions").addObject().put("issueId",id).putNull("findings").putNull("reason");raw.putArray("additionalFindings");
        packet.putObject("validatedResult").putArray("hints").add(hint);
        packet.putObject("finalResult").putArray("hints").add(hint);
        for(var node:List.of(group,packet)){
            var observed=node.putObject("monetaryObservation").put("protocol",ContractMonetaryObservation.PROTOCOL).put("mode","OBSERVE");observed.putArray("warnings");observed.putArray("workWarnings");
        }
        ((ObjectNode)packet.path("finalResult")).set("monetaryObservation",packet.path("monetaryObservation"));
    }
    void verify(String trusted) throws Exception {
        var method=Arrays.stream(SemanticMonetaryObservationVerifier.class.getMethods()).filter(m->m.getName().equals("validateFinal")&&m.getParameterCount()==7).findFirst();
        assertThat(method).as("frozen business adapter policy overload").isPresent();
        var finalUnit=unit(packet,2);var observation=SemanticMonetaryObservationVerifier.decode(packet.path("monetaryObservation"),json);
        try{method.orElseThrow().invoke(null,observation,"OBSERVE",List.of(unit(group,1),finalUnit),finalUnit,json,"contract-bounded-hierarchy-v2",trusted);}
        catch(java.lang.reflect.InvocationTargetException failure){throw (RuntimeException)failure.getCause();}
    }
    @Test void frozenAdapterRebuildsOriginalIdentityAndPublicHint() {assertThatCode(()->verify(protocol)).doesNotThrowAnyException();}
    @Test void reorderedModelDecisionsKeepTheActualFirstConsumptionOrder(){
        var second=hint.deepCopy().put("title","第二事项");
        var claims=(com.fasterxml.jackson.databind.node.ArrayNode)group.path("quality").path("groupDigest").path("claims");
        var row=claims.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)row).set(2,json.getNodeFactory().textNode("第二事项"));claims.add(row);
        ((com.fasterxml.jackson.databind.node.ArrayNode)group.path("validatedResult").path("hints")).add(second);
        var identity=json.createObjectNode().put("role","FINAL").put("grouping","a".repeat(64)).put("alias","G1:H2");identity.set("original",second);
        String id="I"+SemanticMonetaryObservationVerifier.canonicalHash(identity).substring(0,32);
        ((com.fasterxml.jackson.databind.node.ArrayNode)packet.path("quality").path("businessAdapter").path("issueIds")).add(id);
        var raw=(com.fasterxml.jackson.databind.node.ArrayNode)packet.path("completion").path("payload").path("decisions");
        raw.insert(0,json.createObjectNode().put("issueId",id).putNull("findings").putNull("reason"));
        var published=(com.fasterxml.jackson.databind.node.ArrayNode)packet.path("finalResult").path("hints");published.removeAll();published.add(second).add(hint);
        assertThatCode(()->verify(protocol)).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"untrusted","issue","duplicate","omission","publicText","groupText","derivedMarker"})
    void forgedOrUnboundBusinessResultsAreRejected(String defect){
        var decision=(ObjectNode)packet.path("completion").path("payload").path("decisions").get(0);
        switch(defect){
            case "issue"->decision.put("issueId","I"+"f".repeat(32));
            case "duplicate"->((com.fasterxml.jackson.databind.node.ArrayNode)packet.path("completion").path("payload").path("decisions")).add(decision.deepCopy());
            case "omission"->((com.fasterxml.jackson.databind.node.ArrayNode)packet.path("completion").path("payload").path("decisions")).removeAll();
            case "publicText"->((ObjectNode)packet.path("finalResult").path("hints").get(0)).put("description","错误改写");
            case "groupText"->((com.fasterxml.jackson.databind.node.ArrayNode)group.path("quality").path("groupDigest").path("claims").get(0)).set(3,json.getNodeFactory().textNode("错误改写"));
            case "derivedMarker"->decision.put("derived",true);
        }
        assertThatThrownBy(()->verify(defect.equals("untrusted")?null:protocol)).isInstanceOf(IllegalStateException.class);
    }
    Unit unit(ObjectNode value,int ordinal){var now=Instant.parse("2026-10-21T01:00:00Z");return new Unit(UUID.randomUUID(),plan,String.valueOf((char)('a'+ordinal-1)).repeat(64),1,
            ordinal,Purpose.SYNTHESIZE,attempt,"e".repeat(64),List.of(),UnitStatus.VERIFIED,"worker",1,now.plusSeconds(30),300000,7,value.toString(),digest(value.toString()),null,
            new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model","d".repeat(64)),ordinal,1,now,now);}
}
