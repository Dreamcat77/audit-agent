package com.fuquan.audit.contract.application.reviewrun.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import com.fuquan.audit.contract.application.closeout.CloseoutCanonical;
import java.util.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;

/** Verifies observation identity without executing a plan, provider call or state transition. */
public final class SemanticMonetaryObservationVerifier {
    private SemanticMonetaryObservationVerifier() { }

    public static void validatePolicy(JsonNode policy, String mode) {
        if (!mode.equals(policy.path("monetaryMode").asText("STRICT"))
                || (policy.has("monetaryObservationProtocol")
                    && !ContractMonetaryObservation.PROTOCOL.equals(policy.path("monetaryObservationProtocol").asText()))
                || ("OBSERVE".equals(mode) && !ContractMonetaryObservation.PROTOCOL
                    .equals(policy.path("monetaryObservationProtocol").asText()))) throw invalid();
    }

    public static ContractMonetaryObservation decode(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try { return json.treeToValue(node, ContractMonetaryObservation.class); }
        catch (java.io.IOException | RuntimeException invalid) { throw invalid(); }
    }

    public static ContractMonetaryObservation validateCheckpoint(JsonNode packet, String mode, ObjectMapper json) {
        var observation = decode(packet.get("monetaryObservation"), json);
        if (observation != null && !mode.equals(observation.mode())) throw invalid();
        if ("OBSERVE".equals(mode) && Set.of("VERIFIED", "REPAIR_REQUIRED").contains(packet.path("status").asText())
                && observation == null) throw invalid();
        if (observation != null && !observation.workWarnings().isEmpty()) throw invalid();
        return observation;
    }

    public static List<ContractMonetaryObservation.WorkWarning> workWarnings(List<Unit> units, String mode, ObjectMapper json) {
        List<ContractMonetaryObservation.WorkWarning> result = new ArrayList<>();
        for (var unit : units.stream().filter(value -> value.status() == UnitStatus.VERIFIED)
                .sorted(Comparator.comparingInt(Unit::ordinal).thenComparingInt(Unit::executionNo)).toList()) {
            final JsonNode packet;
            try { packet = json.readTree(unit.resultJson()); }
            catch (java.io.IOException invalid) { throw invalid(); }
            var observation = validateCheckpoint(packet, mode, json);
            if (observation != null && !observation.warnings().isEmpty()) {
                result.add(new ContractMonetaryObservation.WorkWarning(unit.logicalKey(), unit.ordinal(), unit.purpose().name(),
                        packet.path("operationFingerprint").asText(), observation.warnings().size(),
                        canonicalHash(packet.path("monetaryObservation").path("warnings"))));
            }
        }
        return List.copyOf(result);
    }

    public static void validateFinal(ContractMonetaryObservation observation, String mode, List<Unit> units,
            Unit synthesis, ObjectMapper json) {
        validateFinal(observation, mode, units, synthesis, json, null);
    }

    public static void validateFinal(ContractMonetaryObservation observation, String mode, List<Unit> units,
            Unit synthesis, ObjectMapper json, String expectedHierarchyProtocol) {
        validateFinal(observation,mode,units,synthesis,json,expectedHierarchyProtocol,null);
    }

    public static void validateFinal(ContractMonetaryObservation observation, String mode, List<Unit> units,
            Unit synthesis, ObjectMapper json, String expectedHierarchyProtocol, String expectedBusinessProtocol) {
        boolean v2 = "contract-bounded-hierarchy-v2".equals(expectedHierarchyProtocol);
        boolean business=expectedBusinessProtocol!=null;
        if(business&&(!v2||!SemanticBusinessObservationIdentity.PROTOCOL.equals(expectedBusinessProtocol)))throw invalid();
        if ((expectedHierarchyProtocol != null && !v2 && !"contract-bounded-hierarchy-v1".equals(expectedHierarchyProtocol))
                || (v2 && !"OBSERVE".equals(mode))) throw invalid();
        if (observation == null) { if ("OBSERVE".equals(mode)) throw invalid(); else return; }
        if ("STRICT".equals(mode) && (!observation.warnings().isEmpty() || !observation.workWarnings().isEmpty())) throw invalid();
        if (!mode.equals(observation.mode()) || !observation.workWarnings().equals(workWarnings(units, mode, json))) throw invalid();
        final JsonNode packet;
        try { packet = json.readTree(synthesis.resultJson()); }
        catch (java.io.IOException invalid) { throw invalid(); }
        var checkpointObservation = validateCheckpoint(packet, mode, json);
        var frozenFinalObservation = decode(packet.path("finalResult").get("monetaryObservation"),json);
        if (checkpointObservation == null || !observation.warnings().equals(checkpointObservation.warnings())
                || !observation.equals(frozenFinalObservation)) throw invalid();
        Map<Integer, JsonNode> referenceHints = null;
        if (!v2) for (var row : packet.path("completion").path("payload").path("resolutions"))
            if (row.isArray() && row.size() > 2 && Set.of("PROCESS_RESOLVED", "SCOPED_COUNTEREVIDENCE")
                    .contains(row.get(2).asText())) throw invalid();
        // The trusted frozen policy, not a provider-supplied marker, permits conditional empty targets.
        // Validate v2 even with zero warnings; this inverse is identity checking, not semantic approval.
        if(business){SemanticBusinessObservationIdentity.validate(packet,units,synthesis,json);referenceHints=Map.of();}
        else if (v2 || "contract-hierarchy-provider-references-v2".equals(packet.path("completion").path("payload").path("protocol").asText()))
            referenceHints = referenceHints(packet, units, synthesis, json, expectedHierarchyProtocol);
        for (var warning : observation.warnings()) {
            if ("summary".equals(warning.fieldPath())) {
                if (warning.category() != null) throw invalid();
                continue;
            }
            var newPath = java.util.regex.Pattern.compile("newHints\\[(0|[1-9][0-9]*)\\]\\.description")
                    .matcher(warning.fieldPath());
            if (newPath.matches()) {
                if (referenceHints == null) referenceHints = referenceHints(packet, units, synthesis, json, expectedHierarchyProtocol);
                final int index;
                try { index = Integer.parseInt(newPath.group(1)); }
                catch (NumberFormatException invalid) { throw invalid(); }
                var hint = referenceHints.get(index);
                if (hint == null || warning.category() == null
                        || !warning.category().equals(hint.path("category").asText())
                        || !warning.textSha256().equals(digest(hint.path("title").textValue() + "\n"
                            + hint.path("description").textValue()))) throw invalid();
                continue;
            }
            var path = java.util.regex.Pattern.compile("hints\\[(\\d+)\\]\\.description").matcher(warning.fieldPath());
            if (!path.matches() || warning.category() == null) throw invalid();
            final int ordinal;
            try { ordinal = Integer.parseInt(path.group(1)); }
            catch (NumberFormatException invalid) { throw invalid(); }
            var hints = packet.path("finalResult").path("hints");
            if (!hints.isArray() || ordinal >= hints.size()
                    || !warning.category().equals(hints.get(ordinal).path("category").asText())) throw invalid();
            if(business&&!warning.textSha256().equals(digest(hints.get(ordinal).path("title").asText()+"\n"
                    +hints.get(ordinal).path("description").asText())))throw invalid();
        }
    }

    /** Rebuild only the private reference identity; all public/schema/money gates remain upstream. */
    private static Map<Integer, JsonNode> referenceHints(JsonNode packet, List<Unit> units, Unit synthesis,
            ObjectMapper json, String expectedHierarchyProtocol) {
        var raw = packet.path("completion").path("payload");
        boolean v2 = "contract-bounded-hierarchy-v2".equals(expectedHierarchyProtocol);
        if (!"FINAL".equals(packet.path("role").asText()) || !raw.isObject() || raw.size() != 4
                || !(v2 ? "contract-hierarchy-provider-references-v2" : "contract-hierarchy-provider-references-v1")
                    .equals(raw.path("protocol").asText())
                || !raw.path("summary").isTextual() || !raw.path("newHints").isArray()
                || !raw.path("resolutions").isArray()) throw invalid();
        Map<String, Unit> latest = new HashMap<>();
        Set<String> groupKeys = new HashSet<>();
        for (var unit : units) {
            if (!unit.planId().equals(synthesis.planId())) throw invalid();
            var previous = latest.get(unit.logicalKey());
            if (previous != null && (previous.ordinal() != unit.ordinal()
                    || previous.executionNo() == unit.executionNo())) throw invalid();
            if (previous == null || previous.executionNo() < unit.executionNo()) latest.put(unit.logicalKey(), unit);
            if (unit.resultJson() != null && "GROUP".equals(tree(unit.resultJson(), json).path("role").asText()))
                groupKeys.add(unit.logicalKey());
        }
        var groups = groupKeys.stream().map(latest::get).sorted(Comparator.comparingInt(Unit::ordinal)).toList();
        if (groups.isEmpty() || groups.stream().map(Unit::ordinal).distinct().count() != groups.size()) throw invalid();
        Map<String, JsonNode> sources = new HashMap<>();
        for (int group = 0; group < groups.size(); group++) {
            var unit = groups.get(group);
            if (unit.status() != UnitStatus.VERIFIED || unit.resultJson() == null) throw invalid();
            var value = tree(unit.resultJson(), json);
            var hints = value.path("validatedResult").path("hints");
            if (!"GROUP".equals(value.path("role").asText()) || !hints.isArray()) throw invalid();
            for (int hint = 0; hint < hints.size(); hint++) sources.put("G" + (group + 1) + ":H" + (hint + 1), hints.get(hint));
        }
        var added = raw.path("newHints");
        for (int index = 0; index < added.size(); index++) {
            requireHintText(added.get(index));
            sources.put("N" + index, added.get(index));
        }
        Set<String> seen = new HashSet<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (var row : raw.path("resolutions")) {
            if (!row.isArray() || row.size() != 6 || !row.get(0).isIntegralNumber() || !row.get(0).canConvertToInt()
                    || !row.get(1).isIntegralNumber() || !row.get(1).canConvertToInt()
                    || row.get(0).intValue() < 0 || row.get(1).intValue() < 0
                    || !row.get(3).isArray()) throw invalid();
            boolean process = v2 && "PROCESS_RESOLVED".equals(row.get(2).asText());
            if (process) {
                if (!row.get(3).isEmpty() || !row.get(4).isArray() || row.get(4).isEmpty()
                        || !row.get(5).isTextual() || row.get(5).textValue().isBlank()) throw invalid();
                Set<Integer> basis = new HashSet<>();
                for (var index : row.get(4))
                    if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                            || !basis.add(index.intValue())) throw invalid();
            } else if (!(Set.of("RETAINED", "SCOPED_REPLACEMENT", "COUNTEREVIDENCE", "NEEDS_VERIFICATION")
                    .contains(row.get(2).asText()) || (v2 && "SCOPED_COUNTEREVIDENCE".equals(row.get(2).asText())))
                    || row.get(3).isEmpty()) throw invalid();
            String alias = "G" + ((long) row.get(0).intValue() + 1) + ":H" + ((long) row.get(1).intValue() + 1);
            if (!sources.containsKey(alias) || !seen.add(alias)) throw invalid();
            Set<String> targets = new HashSet<>();
            for (var target : row.get(3)) {
                if (!target.isTextual() || !sources.containsKey(target.textValue()) || !targets.add(target.textValue())) throw invalid();
                positions.computeIfAbsent(target.textValue(), key -> positions.size());
            }
        }
        long inheritedCount = sources.keySet().stream().filter(key -> key.startsWith("G")).count();
        if (seen.size() != inheritedCount) throw invalid();
        for (int index = 0; index < added.size(); index++) positions.computeIfAbsent("N" + index, key -> positions.size());
        var canonicalHints = packet.path("finalResult").path("hints");
        if (!canonicalHints.isArray() || canonicalHints.size() != positions.size()) throw invalid();
        for (var entry : positions.entrySet()) {
            var original = sources.get(entry.getKey()); var restored = canonicalHints.get(entry.getValue());
            requireHintText(original); requireHintText(restored);
            for (var field : List.of("category", "title", "description"))
                if (!original.get(field).equals(restored.get(field))) throw invalid();
        }
        Map<Integer, JsonNode> result = new HashMap<>();
        for (int index = 0; index < added.size(); index++) result.put(index, added.get(index));
        return result;
    }

    private static void requireHintText(JsonNode hint) {
        if (!hint.isObject() || !hint.path("category").isTextual() || !hint.path("title").isTextual()
                || !hint.path("description").isTextual()) throw invalid();
    }
    private static JsonNode tree(String value, ObjectMapper json) {
        try { return json.readTree(value); }
        catch (java.io.IOException | RuntimeException invalid) { throw invalid(); }
    }

    public static String canonicalHash(JsonNode node) { return digest(CloseoutCanonical.json(canonical(node))); }
    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>(); node.forEach(value -> result.add(canonical(value))); return result;
        }
        if (node.isNull()) return null;
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("semantic monetary observation cannot be verified"); }
}
