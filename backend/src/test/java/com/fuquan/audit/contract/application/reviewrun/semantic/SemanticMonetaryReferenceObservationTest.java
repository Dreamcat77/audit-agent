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

class SemanticMonetaryReferenceObservationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-20T01:00:00Z");
    private final UUID planId = UUID.randomUUID(), attemptId = UUID.randomUUID();
    private final ObjectNode packet = json.createObjectNode().put("role", "FINAL").put("status", "VERIFIED");
    private final ObjectNode raw = packet.putObject("completion").putObject("payload")
            .put("protocol", "contract-hierarchy-provider-references-v1").put("summary", "Synthetic final summary");
    private final List<Unit> groups = new ArrayList<>();

    SemanticMonetaryReferenceObservationTest() {
        var a = hint("group-one"); var b = hint("group-two");
        for (var value : List.of(a, b)) {
            var group = json.createObjectNode().put("role", "GROUP").put("status", "VERIFIED");
            group.putObject("validatedResult").putArray("hints").add(value);
            group.putObject("monetaryObservation").put("protocol", ContractMonetaryObservation.PROTOCOL)
                    .put("mode", "OBSERVE").putArray("warnings");
            groups.add(unit(group, groups.size() + 1));
        }
        raw.putArray("newHints").add(hint("new-zero")).add(hint("new-one")).add(hint("new-zero"));
        var rows = raw.putArray("resolutions");
        rows.addArray().add(0).add(0).add("SCOPED_REPLACEMENT").addArray().add("N1").add("G1:H1");
        ((ArrayNode) rows.get(0)).addArray().add(0); ((ArrayNode) rows.get(0)).add("Synthetic reason");
        rows.addArray().add(1).add(0).add("SCOPED_REPLACEMENT").addArray().add("G1:H1").add("N0").add("G2:H1");
        ((ArrayNode) rows.get(1)).addArray().add(0); ((ArrayNode) rows.get(1)).add("Synthetic reason");
        // Independent inverse: first-consumed N1,G1:H1,N0,G2:H1, then unused N2.
        packet.putObject("finalResult").putArray("hints").add(raw.path("newHints").get(1))
                .add(a).add(raw.path("newHints").get(0)).add(b).add(raw.path("newHints").get(2));
        ((ObjectNode) packet.path("finalResult")).set("hints", packet.path("finalResult").path("hints").deepCopy());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void newHintWarningsBindFirstConsumptionAndUnusedAppendWithoutContentDeduplication(int index) {
        assertThatCode(() -> verify("newHints[" + index + "].description", index)).doesNotThrowAnyException();
    }

    @Test void inheritedCanonicalHintWarningsRetainTheirOriginalPath() {
        assertThatCode(() -> verify("hints[1].description", 0)).doesNotThrowAnyException();
    }

    @Test void groupsUseLatestVerifiedExecutionAndOrdinalRatherThanListOrRepairOrder() {
        var old = groups.get(0);
        groups.set(0, copy(old, planId, 2, old.ordinal(), UnitStatus.VERIFIED));
        groups.add(copy(old, planId, 1, old.ordinal(), UnitStatus.REPAIR_REQUIRED));
        Collections.reverse(groups);
        assertThatCode(() -> verify("newHints[0].description", 0)).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(strings = {"crossPlan", "duplicateOrdinal", "duplicateExecution", "latestUnknown", "latestFailed"})
    void staleAmbiguousOrForeignGroupsCannotSupplyReferenceIdentity(String defect) {
        var old = groups.get(0);
        switch (defect) {
            case "crossPlan" -> groups.set(0, copy(old, UUID.randomUUID(), 1, old.ordinal(), UnitStatus.VERIFIED));
            case "duplicateOrdinal" -> groups.set(1, copy(groups.get(1), planId, 1, old.ordinal(), UnitStatus.VERIFIED));
            case "duplicateExecution" -> groups.add(copy(old, planId, 1, old.ordinal(), UnitStatus.VERIFIED));
            case "latestUnknown" -> groups.add(copy(old, planId, 2, old.ordinal(), UnitStatus.UNKNOWN));
            case "latestFailed" -> groups.add(copy(old, planId, 2, old.ordinal(), UnitStatus.FAILED));
        }
        assertThatThrownBy(() -> verify("newHints[0].description", 0)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"protocol", "role", "missingRole", "unknownRef", "boolRef",
            "newOutOfBounds", "groupOutOfBounds", "boolGroup", "missingGroup", "duplicateSource",
            "wrongRawCategory", "wrongFinalCategory", "wrongFinalText", "wrongHash", "wrongPosition",
            "newIndexOutOfBounds", "nonObjectNewHint", "nonTextTitle"})
    void unprovenRawOrCanonicalIdentityCannotAuthorizeANewHintWarning(String defect) {
        var first = (ArrayNode) raw.path("resolutions").get(0);
        switch (defect) {
            case "protocol" -> raw.put("protocol", "old-protocol");
            case "role" -> packet.put("role", "GROUP");
            case "missingRole" -> packet.remove("role");
            case "unknownRef" -> ((ArrayNode) first.get(3)).set(0, json.getNodeFactory().textNode("A1:H1"));
            case "boolRef" -> ((ArrayNode) first.get(3)).set(0, json.getNodeFactory().booleanNode(true));
            case "newOutOfBounds" -> ((ArrayNode) first.get(3)).set(0, json.getNodeFactory().textNode("N3"));
            case "groupOutOfBounds" -> ((ArrayNode) first.get(3)).set(1, json.getNodeFactory().textNode("G3:H1"));
            case "boolGroup" -> first.set(0, json.getNodeFactory().booleanNode(false));
            case "missingGroup" -> groups.remove(1);
            case "duplicateSource" -> ((ArrayNode) raw.path("resolutions").get(1)).set(0, json.getNodeFactory().numberNode(0));
            case "wrongRawCategory" -> ((ObjectNode) raw.path("newHints").get(0)).put("category", "CONTRACT_STRUCTURE");
            case "wrongFinalCategory" -> ((ObjectNode) packet.path("finalResult").path("hints").get(2)).put("category", "CONTRACT_STRUCTURE");
            case "wrongFinalText" -> ((ObjectNode) packet.path("finalResult").path("hints").get(2)).put("description", "Different text");
            case "wrongPosition" -> { var hints = (ArrayNode) packet.path("finalResult").path("hints"); var old = hints.get(0); hints.set(0, hints.get(2)); hints.set(2, old); }
            case "nonObjectNewHint" -> ((ArrayNode) raw.path("newHints")).set(0, json.getNodeFactory().textNode("not a hint"));
            case "nonTextTitle" -> ((ObjectNode) raw.path("newHints").get(0)).put("title", true);
            default -> { }
        }
        assertThatThrownBy(() -> verify("newHints[" + (defect.equals("newIndexOutOfBounds") ? 3 : 0) + "].description",
                0, defect.equals("wrongHash") ? "0".repeat(64) : null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void legacyCanonicalPathsAndSummaryRemainReadableWithoutReferenceWire() {
        packet.remove("role"); packet.remove("completion");
        assertThatCode(() -> verify("hints[1].description", 0)).doesNotThrowAnyException();
        assertThatCode(() -> verify("summary", 0)).doesNotThrowAnyException();
    }

    private void verify(String path, int index) { verify(path, index, null); }
    private void verify(String path, int index, String forcedHash) {
        String textHash = forcedHash != null ? forcedHash : digest("new-zero\nPayment 99999 requires checking");
        if (path.startsWith("newHints[1]")) textHash = digest("new-one\nPayment 99999 requires checking");
        var observation = packet.putObject("monetaryObservation").put("protocol", ContractMonetaryObservation.PROTOCOL).put("mode", "OBSERVE");
        var warning = observation.putArray("warnings").addObject().put("fieldPath", path).put("errorKind", "MONETARY_VALUE")
                .put("textSha256", textHash);
        if (path.equals("summary")) warning.putNull("category"); else warning.put("category", "DELIVERY_TERMS");
        warning.putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        var finalObservation = observation.deepCopy();
        finalObservation.putArray("workWarnings").addObject().put("logicalKey", "c".repeat(64)).put("ordinal", 3)
                .put("purpose", "SYNTHESIZE").put("operationFingerprint", "d".repeat(64)).put("warningCount", 1)
                .put("warningsSha256", SemanticMonetaryObservationVerifier.canonicalHash(observation.path("warnings")));
        ((ObjectNode) packet.path("finalResult")).set("monetaryObservation", finalObservation);
        packet.put("operationFingerprint", "d".repeat(64));
        var synthesis = unit(packet, 3); var units = new ArrayList<>(groups); units.add(synthesis);
        SemanticMonetaryObservationVerifier.validateFinal(SemanticMonetaryObservationVerifier.decode(finalObservation, json),
                "OBSERVE", units, synthesis, json);
    }
    private ObjectNode hint(String title) {
        var hint = json.createObjectNode().put("category", "DELIVERY_TERMS").put("attention", "REVIEW")
                .put("title", title).put("description", "Payment 99999 requires checking")
                .put("claimKind", "AMBIGUOUS_CLAUSE").putNull("unavailableMaterialCategory");
        hint.putArray("problemEvidenceIds").add("e0"); hint.putArray("comparisonEvidenceIds").add("e1");
        hint.putObject("obligationScope").put("topic", "DELIVERY").put("responsibleParty", "UNSPECIFIED").putNull("trigger");
        return hint;
    }
    private Unit unit(ObjectNode value, int ordinal) {
        return new Unit(UUID.randomUUID(), planId, String.valueOf((char) ('a' + ordinal - 1)).repeat(64), 1,
                ordinal, Purpose.SYNTHESIZE, attemptId, "e".repeat(64), List.of(), UnitStatus.VERIFIED, "worker", 1,
                now.plusSeconds(30), 300000, 7, value.toString(), digest(value.toString()), null,
                new CallMetadata(UUID.randomUUID(), "OPENAI_COMPATIBLE", "unit-model", "d".repeat(64)), ordinal, 1, now, now);
    }
    private Unit copy(Unit value, UUID plan, int execution, int ordinal, UnitStatus status) {
        return new Unit(UUID.randomUUID(), plan, value.logicalKey(), execution, ordinal, value.purpose(), value.attemptId(),
                value.inputFingerprint(), value.dependencies(), status, value.owner(), value.fence(), value.leaseUntil(),
                value.reservedMs(), value.durationMs(), value.resultJson(), value.resultSha256(), value.safeReason(),
                value.call(), value.callOrdinal(), value.versionNo(), value.createdAt(), value.updatedAt());
    }
}
