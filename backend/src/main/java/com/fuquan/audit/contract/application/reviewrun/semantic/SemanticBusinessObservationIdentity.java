package com.fuquan.audit.contract.application.reviewrun.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;

/** Independent publication identity inverse, not a replacement semantic validator. */
final class SemanticBusinessObservationIdentity {
    static final String PROTOCOL="contract-semantic-business-issues-v1";
    private static final List<String> CATEGORIES=List.of("CONTRACT_STRUCTURE","PROJECT_IDENTITY","SUPPLIER_IDENTITY","DELIVERY_TERMS","SERVICE_WARRANTY","SCOPE_AND_SUBCONTRACTING","BREACH_LIABILITY","TERM_TERMINATION","OTHER_TEXT");
    private static final List<String> ATTENTIONS=List.of("INFO","REVIEW","INSUFFICIENT");
    private static final List<String> KINDS=List.of("CLAUSE_PRESENT","LOCAL_NOT_SEEN","GLOBAL_CLAUSE_NOT_FOUND","MATERIAL_MISSING","AMBIGUOUS_CLAUSE","CROSS_MATERIAL_CONFLICT");
    private SemanticBusinessObservationIdentity(){}
    static void validate(JsonNode packet,List<Unit> units,Unit synthesis,ObjectMapper json){
        var raw=packet.path("completion").path("payload");var quality=packet.path("quality");
        if(!"FINAL".equals(packet.path("role").asText())||!fields(raw,"protocol","summary","decisions","additionalFindings")
                ||!PROTOCOL.equals(raw.path("protocol").asText())||!raw.path("summary").isTextual()
                ||!raw.path("decisions").isArray()||!raw.path("additionalFindings").isArray()
                ||!PROTOCOL.equals(quality.path("businessAdapter").path("protocol").asText()))throw invalid();
        String grouping=packet.path("groupingFingerprint").asText();if(!grouping.matches("[a-f0-9]{64}"))throw invalid();
        Map<String,Unit> latest=new HashMap<>();
        for(var unit:units){
            if(!unit.planId().equals(synthesis.planId()))throw invalid();
            var previous=latest.get(unit.logicalKey());
            if(previous!=null&&(previous.ordinal()!=unit.ordinal()||previous.executionNo()==unit.executionNo()))throw invalid();
            if(previous==null||previous.executionNo()<unit.executionNo())latest.put(unit.logicalKey(),unit);
        }
        var groups=latest.values().stream().filter(u->u.resultJson()!=null&&"GROUP".equals(tree(u.resultJson(),json).path("role").asText())).sorted(Comparator.comparingInt(Unit::ordinal)).toList();
        if(groups.isEmpty())throw invalid();
        Map<String,JsonNode> originals=new LinkedHashMap<>();
        var registry=quality.path("finalEvidenceProof").path("evidenceRegistry");var scopes=quality.path("originalObligationScopes");
        if(!registry.isArray()||!scopes.isArray())throw invalid();
        for(int gi=0;gi<groups.size();gi++){
            var unit=groups.get(gi);if(unit.status()!=UnitStatus.VERIFIED)throw invalid();
            var group=tree(unit.resultJson(),json);var claims=group.path("quality").path("groupDigest").path("claims");
            var normalized=group.path("validatedResult").path("hints");
            if(!claims.isArray()||!normalized.isArray()||claims.size()!=normalized.size())throw invalid();
            for(int hi=0;hi<claims.size();hi++){
                var row=claims.get(hi);if(!row.isArray()||row.size()!=9)throw invalid();
                var original=json.createObjectNode().put("category",literal(CATEGORIES,row.get(0))).put("attention",literal(ATTENTIONS,row.get(1)))
                        .put("claimKind",literal(KINDS,row.get(4)));
                original.set("title",row.get(2));original.set("description",row.get(3));
                original.set("obligationScope",at(scopes,row.get(5)));original.set("problemEvidenceIds",references(registry,row.get(6),json));
                original.set("comparisonEvidenceIds",references(registry,row.get(7),json));original.set("unavailableMaterialCategory",row.get(8));
                compareText(original,normalized.get(hi));
                var identity=json.createObjectNode().put("role","FINAL").put("grouping",grouping).put("alias","G"+(gi+1)+":H"+(hi+1));identity.set("original",original);
                String issue="I"+SemanticMonetaryObservationVerifier.canonicalHash(identity).substring(0,32);
                if(originals.putIfAbsent(issue,original)!=null)throw invalid();
            }
        }
        var frozenIssues=quality.path("businessAdapter").path("issueIds");
        if(!frozenIssues.isArray()||frozenIssues.size()!=originals.size()||raw.path("decisions").size()!=originals.size())throw invalid();
        int oi=0;for(String id:originals.keySet())if(!id.equals(frozenIssues.get(oi++).asText()))throw invalid();
        Map<String,JsonNode> decisions=new HashMap<>();
        for(var row:raw.path("decisions")){
            if(!fields(row,"issueId","findings","reason")||!row.path("issueId").isTextual()||!originals.containsKey(row.path("issueId").asText())
                    ||decisions.putIfAbsent(row.path("issueId").asText(),row)!=null)throw invalid();
        }
        Map<String,JsonNode> expanded=new LinkedHashMap<>();
        for(var row:raw.path("decisions")){
            var original=originals.get(row.path("issueId").asText());if(original==null)throw invalid();
            if(row.get("findings").isNull()){
                if(!row.get("reason").isNull()||"LOCAL_NOT_SEEN".equals(original.path("claimKind").asText()))throw invalid();
                add(expanded,original);
            }else{
                if(!row.get("findings").isArray()||row.get("findings").isEmpty()||row.get("findings").size()>100
                        ||!row.path("reason").isTextual()||row.path("reason").asText().isBlank())throw invalid();
                for(var finding:row.get("findings"))add(expanded,finding);
            }
        }
        for(var finding:raw.path("additionalFindings"))add(expanded,finding);
        var published=packet.path("finalResult").path("hints");
        if(!published.isArray()||published.size()!=expanded.size())throw invalid();
        int index=0;for(var finding:expanded.values())compareText(finding,published.get(index++));
    }
    private static void add(Map<String,JsonNode> result,JsonNode hint){
        if(!fields(hint,"category","attention","title","description","claimKind","obligationScope","problemEvidenceIds","comparisonEvidenceIds","unavailableMaterialCategory")
                ||!hint.path("problemEvidenceIds").isArray()||hint.path("problemEvidenceIds").size()>50
                ||!hint.path("comparisonEvidenceIds").isArray()||hint.path("comparisonEvidenceIds").size()>50)throw invalid();
        result.putIfAbsent(SemanticMonetaryObservationVerifier.canonicalHash(hint),hint);if(result.size()>100)throw invalid();
    }
    private static boolean fields(JsonNode node,String...names){if(!node.isObject()||node.size()!=names.length)return false;for(String name:names)if(!node.has(name))return false;return true;}
    private static JsonNode at(JsonNode values,JsonNode index){if(!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>=values.size())throw invalid();return values.get(index.intValue());}
    private static String literal(List<String> values,JsonNode index){if(!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>=values.size())throw invalid();return values.get(index.intValue());}
    private static JsonNode references(JsonNode registry,JsonNode indexes,ObjectMapper json){if(!indexes.isArray())throw invalid();var result=json.createArrayNode();for(var index:indexes)result.add(at(registry,index));return result;}
    private static void compareText(JsonNode original,JsonNode restored){for(String key:List.of("category","title","description"))if(!original.path(key).isTextual()||!original.get(key).equals(restored.get(key)))throw invalid();}
    private static JsonNode tree(String value,ObjectMapper json){try{return json.readTree(value);}catch(java.io.IOException failure){throw invalid();}}
    private static IllegalStateException invalid(){return new IllegalStateException("semantic business identity cannot be verified");}
}
