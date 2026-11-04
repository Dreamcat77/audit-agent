package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticMonetaryProcessReferenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID plan = UUID.randomUUID(), attempt = UUID.randomUUID();
    private final ObjectNode packet = json.createObjectNode().put("role", "FINAL").put("status", "VERIFIED")
            .put("operationFingerprint", "d".repeat(64));
    private final ObjectNode raw = packet.putObject("completion").putObject("payload")
            .put("protocol", "contract-hierarchy-provider-references-v2").put("summary", "Synthetic summary");
    private final List<Unit> groups = new ArrayList<>();
    private String trusted = "contract-bounded-hierarchy-v2";

    SemanticMonetaryProcessReferenceTest() {
        for (int i = 0; i < 3; i++) {
            var group = json.createObjectNode().put("role", "GROUP").put("status", "VERIFIED");
            group.putObject("validatedResult").putArray("hints").add(hint("same inherited"));
            group.putObject("monetaryObservation").put("protocol", ContractMonetaryObservation.PROTOCOL)
                    .put("mode", "OBSERVE").putArray("warnings");
            groups.add(unit(group, i + 1));
        }
        raw.putArray("newHints").add(hint("new money")).add(hint("new money"));
        var rows = raw.putArray("resolutions");
        row(rows, 0, "PROCESS_RESOLVED", List.of());
        row(rows, 1, "RETAINED", List.of("G2:H1", "N0"));
        row(rows, 2, "RETAINED", List.of("G3:H1"));
        // Same text, distinct aliases and unused N1 all remain distinct.
        packet.putObject("finalResult").putArray("hints").add(hint("same inherited"))
                .add(hint("new money")).add(hint("same inherited")).add(hint("new money"));
    }

    @ParameterizedTest @ValueSource(strings = {"none", "summary", "hints[0].description", "newHints[0].description", "newHints[1].description", "all"})
    void trustedV2PreservesProcessInverseAndEveryWarning(String path) {
        assertThatCode(() -> verify(path)).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(strings = {"untrusted", "v1Policy", "v1Wire", "v1Process", "groupRole", "basisNull", "basisEmpty",
            "basisBoolean", "basisNegative", "basisOverflow", "basisDuplicate", "reasonNull", "reasonEmpty", "processTarget",
            "otherEmpty", "unknownRef", "boolRef", "missingAlias", "wrongCanonical", "contentDedup", "wrongHash"})
    void malformedV2CannotHideBehindNoWarnings(String defect) {
        var first = (ArrayNode) raw.path("resolutions").get(0);
        switch (defect) {
            case "untrusted" -> trusted = null;
            case "v1Policy" -> trusted = "contract-bounded-hierarchy-v1";
            case "v1Wire" -> raw.put("protocol", "contract-hierarchy-provider-references-v1");
            case "v1Process" -> { trusted = "contract-bounded-hierarchy-v1";
                raw.put("protocol", "contract-hierarchy-provider-references-v1"); }
            case "groupRole" -> packet.put("role", "GROUP");
            case "basisNull" -> first.set(4, json.nullNode());
            case "basisEmpty" -> first.set(4, json.createArrayNode());
            case "basisBoolean" -> ((ArrayNode) first.get(4)).set(0, json.getNodeFactory().booleanNode(true));
            case "basisNegative" -> ((ArrayNode) first.get(4)).set(0, json.getNodeFactory().numberNode(-1));
            case "basisOverflow" -> ((ArrayNode) first.get(4)).set(0, json.getNodeFactory().numberNode(Long.MAX_VALUE));
            case "basisDuplicate" -> ((ArrayNode) first.get(4)).add(0);
            case "reasonNull" -> first.set(5, json.nullNode());
            case "reasonEmpty" -> first.set(5, json.getNodeFactory().textNode(" "));
            case "processTarget" -> ((ArrayNode) first.get(3)).add("N0");
            case "otherEmpty" -> ((ArrayNode) raw.path("resolutions").get(1)).set(3, json.createArrayNode());
            case "unknownRef" -> ((ArrayNode) raw.path("resolutions").get(1).get(3)).set(0, json.getNodeFactory().textNode("G8:H1"));
            case "boolRef" -> ((ArrayNode) raw.path("resolutions").get(1).get(3)).set(0, json.getNodeFactory().booleanNode(true));
            case "missingAlias" -> ((ArrayNode) raw.path("resolutions")).remove(2);
            case "wrongCanonical" -> ((ObjectNode) packet.path("finalResult").path("hints").get(1)).put("title", "changed");
            case "contentDedup" -> ((ArrayNode) packet.path("finalResult").path("hints")).remove(2);
        }
        assertThatThrownBy(() -> verify(defect.equals("wrongHash") ? "wrongHash" : "none"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void oldEntryPointCannotAuthorizeV2EvenWithoutWarnings() {
        trusted = null;
        assertThatThrownBy(() -> verify("none")).isInstanceOf(IllegalStateException.class);
    }

    @Test void scopedCounterevidenceUsesOrdinaryNonemptyTargetsOnlyUnderV2() {
        var second = (ArrayNode) raw.path("resolutions").get(1);
        second.set(2, json.getNodeFactory().textNode("SCOPED_COUNTEREVIDENCE"));
        assertThatCode(() -> verify("all")).doesNotThrowAnyException();
        second.set(3, json.createArrayNode());
        assertThatThrownBy(() -> verify("none")).isInstanceOf(IllegalStateException.class);
    }

    @Test void oldPolicyAndOldWireRejectScopedCounterevidenceEvenWithoutWarnings() {
        trusted = "contract-bounded-hierarchy-v1";
        raw.put("protocol", "contract-hierarchy-provider-references-v1");
        var first = (ArrayNode) raw.path("resolutions").get(0);
        first.set(2, json.getNodeFactory().textNode("SCOPED_COUNTEREVIDENCE"));
        first.set(3, json.createArrayNode().add("G1:H1"));
        assertThatThrownBy(() -> verify("none")).isInstanceOf(IllegalStateException.class);
    }

    private void verify(String path) {
        var observation = packet.putObject("monetaryObservation").put("protocol", ContractMonetaryObservation.PROTOCOL).put("mode", "OBSERVE");
        var warnings = observation.putArray("warnings");
        for (String field : path.equals("all") ? List.of("summary", "hints[0].description", "newHints[0].description", "newHints[1].description")
                : path.equals("none") ? List.<String>of() : List.of(path.equals("wrongHash") ? "newHints[0].description" : path)) {
            var warning = warnings.addObject().put("fieldPath", field).put("errorKind", "MONETARY_VALUE")
                    .put("textSha256", path.equals("wrongHash") ? "0".repeat(64) : digest("new money\nPayment 99999 requires checking"));
            if (field.equals("summary")) warning.putNull("category"); else warning.put("category", "DELIVERY_TERMS");
            warning.putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        }
        var finalObservation = observation.deepCopy();
        var work = finalObservation.putArray("workWarnings");
        if (!warnings.isEmpty()) work.addObject().put("logicalKey", "d".repeat(64)).put("ordinal", 4)
                .put("purpose", "SYNTHESIZE").put("operationFingerprint", "d".repeat(64)).put("warningCount", warnings.size())
                .put("warningsSha256", SemanticMonetaryObservationVerifier.canonicalHash(warnings));
        ((ObjectNode) packet.path("finalResult")).set("monetaryObservation", finalObservation);
        var synthesis = unit(packet, 4); var units = new ArrayList<>(groups); units.add(synthesis);
        var decoded = SemanticMonetaryObservationVerifier.decode(finalObservation, json);
        if (trusted == null) {
            SemanticMonetaryObservationVerifier.validateFinal(decoded, "OBSERVE", units, synthesis, json);
        } else SemanticMonetaryObservationVerifier.validateFinal(decoded, "OBSERVE", units, synthesis, json, trusted);
    }

    private void row(ArrayNode rows, int group, String disposition, List<String> targets) {
        var row = rows.addArray().add(group).add(0).add(disposition);
        var refs = row.addArray(); targets.forEach(refs::add);
        row.addArray().add(0); row.add("Synthetic cited process explanation");
    }
    private ObjectNode hint(String title) {
        return json.createObjectNode().put("category", "DELIVERY_TERMS").put("title", title)
                .put("description", "Payment 99999 requires checking");
    }
    private Unit unit(ObjectNode value, int ordinal) {
        var now = Instant.parse("2026-10-20T01:00:00Z");
        return new Unit(UUID.randomUUID(), plan, String.valueOf((char) ('a' + ordinal - 1)).repeat(64), 1,
                ordinal, Purpose.SYNTHESIZE, attempt, "e".repeat(64), List.of(), UnitStatus.VERIFIED, "worker", 1,
                now.plusSeconds(30), 300000, 7, value.toString(), digest(value.toString()), null,
                new CallMetadata(UUID.randomUUID(), "OPENAI_COMPATIBLE", "unit-model", "d".repeat(64)), ordinal, 1, now, now);
    }
}
