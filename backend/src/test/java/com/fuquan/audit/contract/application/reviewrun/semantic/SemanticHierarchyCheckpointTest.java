package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exercises the existing transaction port, not a parallel hierarchy ledger. */
class SemanticHierarchyCheckpointTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String INPUT = "a".repeat(64), POLICY = "b".repeat(64);
    static String key(String stage) { return digest("synthetic-hierarchy-" + stage); }

    static ObjectNode manifest(int analyses) {
        var m = JSON.createObjectNode().put("version", 2).put("timeLimitMs", 900000)
                .put("repairLimit", 2).put("unitTimeLimitMs", 300000)
                .put("maxOutputTokensPerCall", 6144);
        m.putObject("policy").put("version", "contract-semantic-work-v3")
                .put("provider", "OPENAI_COMPATIBLE").put("model", "synthetic")
                .put("monetaryMode", "OBSERVE").put("monetaryObservationProtocol", "contract-money-observation-v1")
                .put("hierarchyProtocol", "contract-bounded-hierarchy-v1")
                .put("reasoningEffort", "none").put("transport", "PROMPT_ONLY")
                .put("maxOutputTokens", 6144).put("automaticTransportRetries", 0);
        var units = m.putArray("units");
        for (int i = 1; i <= analyses; i++) {
            var a = units.addObject().put("logicalKey", key("A" + i)).put("ordinal", i)
                    .put("purpose", "ANALYZE").put("role", "ANALYSIS").put("inputFingerprint", INPUT);
            var dependencies = a.putArray("dependencies");
            if (i > 1) dependencies.add(key("A" + (i - 1)));
        }
        int groups = (analyses + 5) / 6;
        var h = m.putObject("hierarchy").put("version", 1)
                .put("finalLogicalKey", key("F")).put("membershipStrategy", "scope-file-size-v1")
                .put("maximumGroups", 3).put("leafIdentity", "analysis-logical-key+hint-ordinal")
                .put("decisionAfter", "ALL_ANALYSES_VERIFIED")
                .put("groupInputMaxChars", 12000).put("finalInputMaxChars", 12000);
        var groupKeys = h.putArray("groupLogicalKeys");
        for (int i = 1; i <= groups; i++) {
            groupKeys.add(key("G" + i));
            var g = units.addObject().put("logicalKey", key("G" + i)).put("ordinal", analyses + i)
                    .put("purpose", "SYNTHESIZE").put("role", "GROUP").put("inputFingerprint", INPUT);
            var dependencies = g.putArray("dependencies");
            for (int a = 1; a <= analyses; a++) dependencies.add(key("A" + a));
        }
        var f = units.addObject().put("logicalKey", key("F")).put("ordinal", analyses + groups + 1)
                .put("purpose", "SYNTHESIZE").put("role", "FINAL").put("inputFingerprint", INPUT);
        var dependencies = f.putArray("dependencies");
        for (int a = 1; a <= analyses; a++) dependencies.add(key("A" + a));
        for (int g = 1; g <= groups; g++) dependencies.add(key("G" + g));
        m.put("callLimit", units.size() + 2).put("maxTotalOutputTokens", (units.size() + 2) * 6144);
        return m;
    }

    static List<UnitSpec> specs(ObjectNode manifest) {
        var result = new ArrayList<UnitSpec>();
        for (var u : manifest.path("units")) {
            var dependencies = new ArrayList<String>(); u.path("dependencies").forEach(v -> dependencies.add(v.asText()));
            result.add(new UnitSpec(u.path("logicalKey").asText(), u.path("ordinal").asInt(),
                    Purpose.valueOf(u.path("purpose").asText()), u.path("inputFingerprint").asText(), dependencies));
        }
        return result;
    }

    static PlanSpec planSpec(ObjectNode m) {
        var envelope = JSON.createObjectNode(); envelope.set("pythonManifest", m);
        return new PlanSpec(INPUT, POLICY, envelope.toString(), SemanticCheckpointCoordinatorTest.NOW.plusSeconds(900),
                900000, m.path("callLimit").asInt(), 2);
    }

    @Test void sevenAnalysesTwoGroupsAndFinalUseOnlyExistingRowsAndSharedBudget() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(7);
        var plan = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var access = f.access(plan);
        assertThat(f.repo.units).hasSize(10); assertThat(plan.spec().callLimit()).isEqualTo(12);
        assertThatThrownBy(() -> f.sent(access, key("G1"), Purpose.SYNTHESIZE)).isInstanceOf(IllegalStateException.class);
        for (int a = 1; a <= 7; a++) f.done(access, f.sent(access, key("A" + a), Purpose.ANALYZE));
        f.done(access, f.sent(access, key("G1"), Purpose.SYNTHESIZE));
        assertThatThrownBy(() -> f.sent(access, key("F"), Purpose.SYNTHESIZE)).isInstanceOf(IllegalStateException.class);
        f.done(access, f.sent(access, key("G2"), Purpose.SYNTHESIZE));
        var sent = f.sent(access, key("F"), Purpose.SYNTHESIZE);
        f.service.completeUnit(access, sent.executionId(), UnitStatus.VERIFIED, "{\"final\":true}", digest("{\"final\":true}"), 7, f.usage, null);
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        var complete = f.service.markComplete(access, digest("{\"final\":true}"));
        assertThat(complete.usedCalls()).isEqualTo(10); assertThat(complete.usedRepairs()).isZero();
        assertThat(complete.spec().deadlineAt()).isEqualTo(plan.spec().deadlineAt());
        assertThat(f.audits).hasSize(10);
    }

    @Test void groupRepairCannotBecomeFinalAndRestartRetainsAllVerifiedWorkAndCharges() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(1);
        var plan = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var access = f.access(plan);
        f.done(access, f.sent(access, key("A1"), Purpose.ANALYZE));
        var rejected = f.sent(access, key("G1"), Purpose.SYNTHESIZE);
        f.service.completeUnit(access, rejected.executionId(), UnitStatus.REPAIR_REQUIRED, "{}", digest("{}"), 7, f.usage, "INVALID_TYPED_SCHEMA");
        var repaired = f.sent(access, key("G1"), Purpose.REPAIR); f.done(access, repaired);
        assertThat(repaired.executionNo()).isEqualTo(2);
        f.replaceAttempt("worker-b", 2);
        var reopened = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var next = f.access(reopened);
        assertThat(reopened.usedCalls()).isEqualTo(3); assertThat(reopened.usedRepairs()).isEqualTo(1);
        assertThat(reopened.spec().deadlineAt()).isEqualTo(plan.spec().deadlineAt());
        var sent = f.sent(next, key("F"), Purpose.SYNTHESIZE);
        f.service.completeUnit(next, sent.executionId(), UnitStatus.VERIFIED, "{\"final\":true}", digest("{\"final\":true}"), 7, f.usage, null);
        assertThatThrownBy(() -> f.service.markComplete(next, digest("{}"))).isInstanceOf(IllegalStateException.class);
        assertThat(f.service.markComplete(next, digest("{\"final\":true}")).usedCalls()).isEqualTo(4);
        assertThat(f.audits).hasSize(4);
    }

    @Test void seventeenAnalysesCannotDispatchBySpendingFewerThanTwoRepairs() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(17);
        assertThatThrownBy(() -> f.service.openOrClaim(f.parent, planSpec(m), specs(m))).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.repo.plan).isNull(); assertThat(f.audits).isEmpty();
    }

    @Test void sixteenAnalysesFreezeExactlyTwentyInitialNodesAndTwentyTwoMaximumCalls() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(16);
        var opened = f.service.openOrClaim(f.parent, planSpec(m), specs(m));
        assertThat(f.repo.units).hasSize(20); assertThat(opened.spec().callLimit()).isEqualTo(22);
        assertThat(opened.spec().repairLimit()).isEqualTo(2); assertThat(opened.usedCalls()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "missing-group", "missing-analysis", "extra-key", "input", "ordinal", "dependencies", "purpose"})
    void completionRequiresExactFrozenInitialAndLatestKeysetsAndEveryInitialRowSpec(String defect) {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(1);
        var opened = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var access = f.access(opened);
        for (var spec : specs(m)) f.done(access, f.sent(access, spec.logicalKey(), spec.purpose()));
        var group = f.repo.units.values().stream().filter(u -> u.logicalKey().equals(key("G1"))).findFirst().orElseThrow();
        if (defect.equals("missing-group") || defect.equals("missing-analysis")) {
            String missing = key(defect.equals("missing-group") ? "G1" : "A1");
            f.repo.units.values().removeIf(u -> u.logicalKey().equals(missing));
        } else if (defect.equals("extra-key")) {
            var extra = copySpec(group, UUID.randomUUID(), key("extra"), group.ordinal() + 2,
                    Purpose.ANALYZE, INPUT, List.of()); f.repo.units.put(extra.executionId(), extra);
        } else {
            var changed = copySpec(group, group.executionId(), group.logicalKey(),
                    defect.equals("ordinal") ? 4 : group.ordinal(), defect.equals("purpose") ? Purpose.ANALYZE : group.purpose(),
                    defect.equals("input") ? key("forged-input") : group.inputFingerprint(),
                    defect.equals("dependencies") ? List.of() : group.dependencies());
            f.repo.units.put(changed.executionId(), changed);
        }
        var before = f.repo.plan;
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan).isEqualTo(before); assertThat(f.repo.plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(f.audits).hasSize(3);
    }

    @Test void completedReplayStillRequiresAllFrozenRows() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(1);
        var opened = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var access = f.access(opened);
        for (var spec : specs(m)) f.done(access, f.sent(access, spec.logicalKey(), spec.purpose()));
        f.service.markComplete(access, digest("{}"));
        f.repo.units.values().removeIf(u -> u.logicalKey().equals(key("G1")));
        var before = f.repo.plan;
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan).isEqualTo(before);
    }

    @Test void intactCompletedReplayAfterDeadlineDoesNotSpendResetOrMutate() {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(1); m.put("timeLimitMs", 1000);
        var envelope = JSON.createObjectNode(); envelope.set("pythonManifest", m);
        var shortSpec = new PlanSpec(INPUT, POLICY, envelope.toString(), SemanticCheckpointCoordinatorTest.NOW.plusSeconds(1), 1000, 5, 2);
        var opened = f.service.openOrClaim(f.parent, shortSpec, specs(m)); var access = f.access(opened);
        for (var spec : specs(m)) f.done(access, f.sent(access, spec.logicalKey(), spec.purpose()));
        var completed = f.service.markComplete(access, digest("{}"));
        f.now.set(SemanticCheckpointCoordinatorTest.NOW.plusSeconds(2));
        assertThat(f.service.markComplete(access, digest("{}"))).isEqualTo(completed);
        assertThat(f.audits).hasSize(3); assertThat(f.repo.plan.usedCalls()).isEqualTo(3);
        assertThat(f.repo.plan.spec().deadlineAt()).isEqualTo(shortSpec.deadlineAt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-initial", "extra-latest-key", "latest-dependencies"})
    void repairedLatestCannotHideMissingInitialExtraKeysOrChangedDependencies(String defect) {
        var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(1);
        var opened = f.service.openOrClaim(f.parent, planSpec(m), specs(m)); var access = f.access(opened);
        f.done(access, f.sent(access, key("A1"), Purpose.ANALYZE));
        var first = f.sent(access, key("G1"), Purpose.SYNTHESIZE);
        f.service.completeUnit(access, first.executionId(), UnitStatus.REPAIR_REQUIRED, "{}", digest("{}"), 7, f.usage, "INVALID_TYPED_SCHEMA");
        var repair = f.done(access, f.sent(access, key("G1"), Purpose.REPAIR));
        f.done(access, f.sent(access, key("F"), Purpose.SYNTHESIZE));
        if (defect.equals("missing-initial")) f.repo.units.remove(first.executionId());
        else {
            var changed = copySpec(repair, defect.equals("extra-latest-key") ? UUID.randomUUID() : repair.executionId(),
                    defect.equals("extra-latest-key") ? key("extra") : repair.logicalKey(), repair.ordinal(), repair.purpose(),
                    repair.inputFingerprint(), defect.equals("latest-dependencies") ? List.of() : repair.dependencies());
            f.repo.units.put(changed.executionId(), changed);
        }
        var before = f.repo.plan;
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan).isEqualTo(before); assertThat(f.audits).hasSize(4);
    }

    static Unit copySpec(Unit row, UUID id, String logicalKey, int ordinal, Purpose purpose,
            String inputFingerprint, List<String> dependencies) {
        return new Unit(id, row.planId(), logicalKey, row.executionNo(), ordinal, purpose, row.attemptId(), inputFingerprint,
                dependencies, row.status(), row.owner(), row.fence(), row.leaseUntil(), row.reservedMs(), row.durationMs(),
                row.resultJson(), row.resultSha256(), row.safeReason(), row.call(), row.callOrdinal(), row.versionNo(), row.createdAt(), row.updatedAt());
    }

    @Test void malformedHierarchyAndPolicyBudgetsNeverCreateRows() {
        for (var mutate : List.<java.util.function.Consumer<ObjectNode>>of(
                m -> ((ObjectNode)m.path("hierarchy")).put("finalLogicalKey", key("G1")),
                m -> ((ObjectNode)m.path("hierarchy")).put("maximumGroups", 4),
                m -> ((ObjectNode)m.path("hierarchy")).put("membershipStrategy", "unbounded-recursive"),
                m -> ((com.fasterxml.jackson.databind.node.ArrayNode)m.path("hierarchy").path("groupLogicalKeys")).add(key("G1")),
                m -> ((ObjectNode)m.path("units").get(7)).putArray("dependencies").add(key("A1")),
                m -> ((ObjectNode)m.path("units").get(9)).putArray("dependencies").add(key("G1")).add(key("G2")),
                m -> ((ObjectNode)m.path("units").get(0)).putArray("dependencies").add(key("F")),
                m -> ((ObjectNode)m.path("units").get(7)).put("purpose", "ANALYZE"),
                m -> ((ObjectNode)m.path("units").get(7)).put("role", "FINAL"),
                m -> m.put("timeLimitMs", 900001), m -> m.put("unitTimeLimitMs", 300001),
                m -> m.put("maxOutputTokensPerCall", 6145),
                m -> ((ObjectNode)m.path("policy")).put("maxOutputTokens", 6145),
                m -> ((ObjectNode)m.path("policy")).put("reasoningEffort", "high"),
                m -> ((ObjectNode)m.path("policy")).put("automaticTransportRetries", 1))) {
            var f = new SemanticCheckpointCoordinatorTest.Fixture(); var m = manifest(7); mutate.accept(m);
            assertThatThrownBy(() -> f.service.openOrClaim(f.parent, planSpec(m), specs(m))).isInstanceOf(IllegalArgumentException.class);
            assertThat(f.repo.plan).isNull(); assertThat(f.audits).isEmpty();
        }
    }
}
