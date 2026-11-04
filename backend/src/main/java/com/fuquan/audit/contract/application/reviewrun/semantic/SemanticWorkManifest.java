package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;
import java.util.*;

/** Pure interpretation of the immutable manifest. No extra persistence or execution policy. */
public final class SemanticWorkManifest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SemanticWorkManifest() { }

    public static String finalLogicalKey(JsonNode manifest, String expectedProtocol) {
        if (manifest == null || !manifest.isObject() || !manifest.path("units").isArray()) throw invalid();
        if (expectedProtocol == null) {
            if (manifest.has("hierarchy") || manifest.path("policy").has("hierarchyProtocol")) throw invalid();
            String result = null;
            for (var unit : manifest.path("units")) {
                if (unit.has("role")) throw invalid();
                if ("SYNTHESIZE".equals(unit.path("purpose").asText())) {
                    if (result != null) throw invalid();
                    result = unit.path("logicalKey").asText(); hash(result);
                }
            }
            if (result == null) throw invalid();
            return result;
        }
        var policy = manifest.path("policy"); var hierarchy = manifest.path("hierarchy");
        boolean v2 = ContractReviewExecutionPolicyResolver.HIERARCHY_V2_PROTOCOL.equals(expectedProtocol);
        boolean business=policy.has("businessAdapterProtocol");
        if(business&&(!v2||!SemanticBusinessObservationIdentity.PROTOCOL.equals(policy.path("businessAdapterProtocol").asText())
                ||!exact(policy.path("serverDerivedMaxIds"),6400)||!exact(policy.path("serverDerivedMaxBytes"),131072)
                ||!"contract-semantic-regeneration-business-v1".equals(policy.path("businessRegenerationProtocol").asText())
                ||!exact(policy.path("businessRegenerationMaxBytes"),24576)))throw invalid();
        if ((!ContractReviewExecutionPolicyResolver.HIERARCHY_PROTOCOL.equals(expectedProtocol) && !v2)
                || !expectedProtocol.equals(policy.path("hierarchyProtocol").asText())
                || !ContractReviewExecutionPolicyResolver.DURABLE_WORK_POLICY_VERSION.equals(policy.path("version").asText())
                || !"OBSERVE".equals(policy.path("monetaryMode").asText())
                || !"none".equals(policy.path("reasoningEffort").asText())
                || !"PROMPT_ONLY".equals(policy.path("transport").asText())
                || !exact(policy.path("maxOutputTokens"), 6144) || !exact(policy.path("automaticTransportRetries"), 0)
                || !hierarchy.isObject() || !exact(hierarchy.path("version"), v2 ? 2 : 1)
                || !"scope-file-size-v1".equals(hierarchy.path("membershipStrategy").asText())
                || !exact(hierarchy.path("maximumGroups"), 3)
                || !"analysis-logical-key+hint-ordinal".equals(hierarchy.path("leafIdentity").asText())
                || !"ALL_ANALYSES_VERIFIED".equals(hierarchy.path("decisionAfter").asText())
                || !exact(hierarchy.path("groupInputMaxChars"), 12000)
                || !exact(hierarchy.path("finalInputMaxChars"), v2 ? 48000 : 12000)) throw invalid();
        if (v2 && (!exact(hierarchy.path("finalInputMaxBytes"), 144000)
                || !"contract-final-frozen-evidence-v1".equals(hierarchy.path("finalEvidenceProtocol").asText())
                || !"contract-final-frozen-evidence-v1".equals(policy.path("finalEvidenceProtocol").asText())
                || !"literal-lines-v1".equals(policy.path("finalEvidenceEncoding").asText())
                || !(business?SemanticBusinessObservationIdentity.PROTOCOL:"contract-hierarchy-provider-references-v2").equals(policy.path("hierarchyProviderDraftProtocol").asText())
                || !exact(policy.path("hierarchyGroupInputMaxChars"), 12000)
                || !exact(policy.path("hierarchyFinalInputMaxChars"), 48000)
                || !exact(policy.path("hierarchyFinalInputMaxBytes"), 144000)
                || !exact(manifest.path("timeLimitMs"), 900000)
                || !exact(manifest.path("unitTimeLimitMs"), 300000))) throw invalid();
        Map<String, JsonNode> byKey = new LinkedHashMap<>(); Set<String> analyses = new LinkedHashSet<>();
        List<String> groups = new ArrayList<>(); String finalKey = null; int ordinal = 0;
        for (var unit : manifest.path("units")) {
            String key = unit.path("logicalKey").asText(); hash(key); hash(unit.path("inputFingerprint").asText());
            if (!exact(unit.path("ordinal"), ++ordinal) || byKey.containsKey(key)) throw invalid();
            var dependencies = keys(unit.path("dependencies"));
            if (!byKey.keySet().containsAll(dependencies)) throw invalid();
            switch (unit.path("role").asText()) {
                case "ANALYSIS" -> {
                    if (!"ANALYZE".equals(unit.path("purpose").asText()) || !analyses.containsAll(dependencies)) throw invalid();
                    analyses.add(key);
                }
                case "GROUP" -> {
                    if (!"SYNTHESIZE".equals(unit.path("purpose").asText())) throw invalid();
                    groups.add(key);
                }
                case "FINAL" -> {
                    if (finalKey != null || !"SYNTHESIZE".equals(unit.path("purpose").asText())) throw invalid();
                    finalKey = key;
                }
                default -> throw invalid();
            }
            byKey.put(key, unit);
        }
        if (analyses.isEmpty() || analyses.size() > 16 || groups.size() != (analyses.size() + 5) / 6
                || groups.size() > 3 || finalKey == null || byKey.size() != analyses.size() + groups.size() + 1
                || byKey.size() > 20 || !finalKey.equals(hierarchy.path("finalLogicalKey").asText())
                || !groups.equals(keys(hierarchy.path("groupLogicalKeys")))) throw invalid();
        for (String group : groups) if (!new HashSet<>(keys(byKey.get(group).path("dependencies"))).equals(analyses)) throw invalid();
        var required = new HashSet<>(analyses); required.addAll(groups);
        if (!new HashSet<>(keys(byKey.get(finalKey).path("dependencies"))).equals(required)
                || !exact(byKey.get(finalKey).path("ordinal"), byKey.size())
                || !positiveBound(manifest.path("timeLimitMs"), 900000)
                || !positiveBound(manifest.path("unitTimeLimitMs"), 300000)
                || !exact(manifest.path("repairLimit"), 2)
                || !exact(manifest.path("callLimit"), byKey.size() + 2)
                || !exact(manifest.path("maxOutputTokensPerCall"), 6144)
                || !exact(manifest.path("maxTotalOutputTokens"), (byKey.size() + 2) * 6144)) throw invalid();
        return finalKey;
    }

    public static String validateStoredSpec(PlanSpec spec, JsonNode manifest, String protocol) {
        String result = finalLogicalKey(manifest, protocol);
        if (protocol != null && (!exact(manifest.path("timeLimitMs"), spec.timeLimitMs())
                || !exact(manifest.path("callLimit"), spec.callLimit())
                || !exact(manifest.path("repairLimit"), spec.repairLimit()))) throw invalid();
        return result;
    }

    static JsonNode pythonManifest(PlanSpec spec) {
        try { return JSON.readTree(spec.planJson()).path("pythonManifest"); }
        catch (java.io.IOException invalid) { throw invalid(); }
    }

    static String finalLogicalKey(PlanSpec spec, List<UnitSpec> units) {
        var manifest = pythonManifest(spec);
        if (manifest.isObject()) {
            String result = validateStoredSpec(spec, manifest,
                    manifest.path("policy").has("hierarchyProtocol") ? manifest.path("policy").path("hierarchyProtocol").asText() : null);
            validateManifestUnits(manifest, units);
            return result;
        }
        String result = null;
        for (var unit : units) if (unit.purpose() == Purpose.SYNTHESIZE) {
            if (result != null) throw invalid(); result = unit.logicalKey();
        }
        if (result == null) throw invalid(); return result;
    }

    static boolean validateHierarchySpecs(PlanSpec spec, List<UnitSpec> units) {
        var manifest = pythonManifest(spec);
        if (!manifest.has("hierarchy") && !manifest.path("policy").has("hierarchyProtocol")) return false;
        finalLogicalKey(spec, units);
        return true;
    }

    private static void validateManifestUnits(JsonNode manifest, List<UnitSpec> units) {
        if (manifest.path("units").size() != units.size()) throw invalid();
        Map<String, UnitSpec> supplied = new HashMap<>();
        for (var unit : units) if (supplied.putIfAbsent(unit.logicalKey(), unit) != null) throw invalid();
        Set<String> required = new HashSet<>();
        for (var planned : manifest.path("units")) {
            if (!required.add(planned.path("logicalKey").asText())) throw invalid();
            var actual = supplied.get(planned.path("logicalKey").asText());
            if (actual == null || actual.ordinal() != planned.path("ordinal").asInt()
                    || !actual.purpose().name().equals(planned.path("purpose").asText())
                    || !actual.inputFingerprint().equals(planned.path("inputFingerprint").asText())
                    || !actual.dependencies().equals(keys(planned.path("dependencies")))) throw invalid();
        }
        if (!required.equals(supplied.keySet())) throw invalid();
    }

    private static List<String> keys(JsonNode node) {
        if (!node.isArray()) throw invalid();
        var result = new ArrayList<String>(); var unique = new HashSet<String>();
        for (var value : node) { if (!value.isTextual()) throw invalid(); String key = value.asText(); hash(key);
            if (!unique.add(key)) throw invalid(); result.add(key); }
        return result;
    }
    private static boolean exact(JsonNode node, long expected) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
    }
    private static boolean positiveBound(JsonNode node, long maximum) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0 && node.longValue() <= maximum;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid frozen semantic work graph"); }
}
