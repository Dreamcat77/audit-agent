package com.fuquan.audit.contract.application.reviewrun.semantic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticMonetaryObservationReaderTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-20T01:00:00Z");
    final UUID runId = UUID.randomUUID(), stepId = UUID.randomUUID(), attemptId = UUID.randomUUID(), planId = UUID.randomUUID();
    final String policy = ContractReviewExecutionPolicyResolver.OBSERVE_MODEL_POLICY_VERSION;
    final ContractReviewRun run = new ContractReviewRun(runId, UUID.randomUUID(), UUID.randomUUID(), 0, null, null, 1,
            "{}", h('a'), "profile", "rule", policy, h('b'), "tester", ContractReviewRunStatus.COMPLETED,
            now, now, now, null, null, null, 1);
    final ContractReviewStep step = new ContractReviewStep(stepId, runId, ContractReviewStepType.SEMANTIC_AI, true,
            ContractReviewStepStatus.COMPLETED, policy, 1, "REVIEW_FINDING_SET", attemptId, null, now, now, 1);
    final ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId, runId, stepId, 1, h('c'),
            ContractReviewStepStatus.COMPLETED, null, null, now, ReviewStepExternalCallState.REQUESTED,
            h('d'), null, "OPENAI_COMPATIBLE", "unit-model", now, now, null, "REVIEW_FINDING_SET", attemptId, 1);
    final ContractReviewFinding finding = new ContractReviewFinding(UUID.randomUUID(), runId, stepId, attemptId, 1,
            "SEMANTIC_HINT", "DELIVERY_TERMS", null, "REVIEW", null, "验收付款安排", "付款99999元需核对", 1,
            policy, ReviewFindingSource.MODEL_ASSISTED, null, "unit-model", null, null, "contract-semantic-result-v1", now, 0);
    final ContractReviewRunCreateResult manifest = new ContractReviewRunCreateResult(run, List.of(), List.of(step), List.of(attempt), false);
    final ReviewStepOutputReadService.StepOutput output = new ReviewStepOutputReadService.StepOutput(run, step,
            List.of(attempt), null, List.of(), List.of(finding), List.of());
    final SemanticCheckpointRepository repository = mock(SemanticCheckpointRepository.class);
    final List<Unit> units = new ArrayList<>();
    final ObjectNode analysis = packet(1, "ANALYZE", h('e'));
    final ObjectNode synthesis = packet(2, "SYNTHESIZE", h('f'));
    Plan plan;

    SemanticMonetaryObservationReader fixture() throws Exception {
        var warnings = json.createArrayNode();
        for (var packet : List.of(analysis, synthesis)) {
            var observation = (ObjectNode) packet.path("monetaryObservation");
            warnings.addObject().put("logicalKey", packet.path("logicalKey").asText()).put("ordinal", packet.path("ordinal").asInt())
                    .put("purpose", packet.path("purpose").asText()).put("operationFingerprint", h('g')).put("warningCount", 1)
                    .put("warningsSha256", SemanticMonetaryObservationVerifier.canonicalHash(observation.path("warnings")));
        }
        var result = synthesis.putObject("finalResult");
        result.putArray("hints").addObject().put("category", "DELIVERY_TERMS").put("attention", "REVIEW")
                .put("title", finding.summary()).put("description", finding.recommendation());
        var finalObservation = synthesis.path("monetaryObservation").deepCopy();
        ((ObjectNode) finalObservation).set("workWarnings", warnings);
        result.set("monetaryObservation", finalObservation);
        units.add(unit(analysis)); units.add(unit(synthesis));
        var python = json.createObjectNode().put("version", 2).put("inputFingerprint", h('h'));
        var pythonPolicy = python.putObject("policy").put("version", "contract-semantic-work-v3")
                .put("monetaryMode", "OBSERVE").put("monetaryObservationProtocol", ContractMonetaryObservation.PROTOCOL);
        String policyHash = SemanticMonetaryObservationVerifier.canonicalHash(pythonPolicy);
        python.put("policyFingerprint", policyHash);
        var planned = python.putArray("units");
        for (var packet : List.of(analysis, synthesis)) planned.addObject().put("logicalKey", packet.path("logicalKey").asText())
                .put("ordinal", packet.path("ordinal").asInt()).put("purpose", packet.path("purpose").asText());
        python.put("planFingerprint", SemanticMonetaryObservationVerifier.canonicalHash(python));
        var envelope = json.createObjectNode().put("frozenInputIdentity", run.inputSnapshotIdentity());
        envelope.set("pythonManifest", python);
        var spec = new PlanSpec(digest(run.inputSnapshotIdentity()+"\n"+h('h')), policyHash, envelope.toString(), now.plusSeconds(900), 900000, 4, 2);
        plan = new Plan(planId, runId, stepId, attemptId, attemptId, spec, "worker", 1, now.plusSeconds(30),
                PlanStatus.COMPLETE, 2, 0, 0, 0, 14, 0, units.get(1).resultSha256(), 1, now, now);
        when(repository.findPlanByStep(stepId)).thenAnswer(call -> Optional.of(plan));
        when(repository.findUnits(planId)).thenAnswer(call -> List.copyOf(units));
        return new SemanticMonetaryObservationReader(repository, json);
    }

    @Test void aFreshReaderReopensModeWarningsSubtypesAndWorkIdentitiesWithoutAnyExecutionPort() throws Exception {
        var reader = fixture();
        var actual = reader.read(manifest, List.of(output));
        assertThat(actual.mode()).isEqualTo("OBSERVE");
        assertThat(actual.warnings()).singleElement().satisfies(value -> assertThat(value.ruleSubtypes()).containsExactly("ABSOLUTE_VALUE"));
        assertThat(actual.workWarnings()).hasSize(2);
        assertThat(new SemanticMonetaryObservationReader(repository, json).read(manifest, List.of(output))).isEqualTo(actual);
    }

    @Test void changedWarningWithAValidRowDigestCannotBorrowTheOldFinalWorkBinding() throws Exception {
        var reader = fixture();
        ((ObjectNode) analysis.path("monetaryObservation").path("warnings").get(0)).put("errorKind", "MONETARY_CALCULATION");
        units.set(0, unit(analysis));
        assertThatThrownBy(() -> reader.read(manifest, List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    @Test void changedFinalCandidateCannotReusePersistedFindingIdentity() throws Exception {
        var reader = fixture();
        ((ObjectNode) synthesis.path("finalResult").path("hints").get(0)).put("description", "不同的新意见");
        replaceSynthesis();
        assertThatThrownBy(() -> reader.read(manifest, List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    @Test void changedFinalWarningListCannotReuseTheOriginalSynthesisWarningsAndWorkBindings() throws Exception {
        var reader = fixture();
        ((ObjectNode) synthesis.path("finalResult").path("monetaryObservation").path("warnings").get(0))
                .put("textSha256", h('x'));
        replaceSynthesis();
        assertThatThrownBy(() -> reader.read(manifest, List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NULL", "CONTRACT_STRUCTURE"})
    void finalHintWarningMustBeTypedAndMatchItsActualFinalHintEvenWhenDiagnosticPacketsAllowNull(String category) throws Exception {
        var reader = fixture();
        for (var observation : List.of(synthesis.path("monetaryObservation"), synthesis.path("finalResult").path("monetaryObservation"))) {
            var warning = (ObjectNode)observation.path("warnings").get(0);
            if ("NULL".equals(category)) warning.putNull("category"); else warning.put("category",category);
        }
        ((ObjectNode)synthesis.path("finalResult").path("monetaryObservation").path("workWarnings").get(1))
                .put("warningsSha256",SemanticMonetaryObservationVerifier.canonicalHash(synthesis.path("monetaryObservation").path("warnings")));
        replaceSynthesis();
        assertThatThrownBy(() -> reader.read(manifest,List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    @Test void removingARequiredAnalysisUnitDoesNotBecomeACompletedRunEvenIfWarningBindingsAreRewritten() throws Exception {
        var reader = fixture();
        ((com.fasterxml.jackson.databind.node.ArrayNode) synthesis.path("finalResult").path("monetaryObservation").path("workWarnings")).remove(0);
        replaceSynthesis(); units.remove(0);
        assertThatThrownBy(() -> reader.read(manifest, List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    @Test void crossRunPlanIsRejectedBeforeExposingWarnings() throws Exception {
        var reader = fixture();
        plan = new Plan(plan.planId(), UUID.randomUUID(), plan.stepId(), plan.originalAttemptId(), plan.currentAttemptId(), plan.spec(),
                plan.owner(), plan.fence(), plan.leaseUntil(), plan.status(), plan.usedCalls(), plan.reservedCalls(), plan.usedRepairs(),
                plan.reservedRepairs(), plan.usedMs(), plan.reservedMs(), plan.finalResultSha256(), plan.versionNo(), plan.createdAt(), plan.updatedAt());
        assertThatThrownBy(() -> reader.read(manifest, List.of(output))).isInstanceOf(IllegalStateException.class);
    }

    private void replaceSynthesis() {
        units.set(1, unit(synthesis));
        plan = new Plan(plan.planId(), runId, stepId, attemptId, attemptId, plan.spec(), plan.owner(), plan.fence(), plan.leaseUntil(), plan.status(),
                2,0,0,0,14,0,units.get(1).resultSha256(), plan.versionNo(),now,now);
    }
    private ObjectNode packet(int ordinal, String purpose, String key) {
        var packet = json.createObjectNode().put("logicalKey", key).put("ordinal", ordinal).put("purpose", purpose)
                .put("operationFingerprint", h('g')).put("status", "VERIFIED");
        var observation = packet.putObject("monetaryObservation").put("protocol", ContractMonetaryObservation.PROTOCOL).put("mode", "OBSERVE");
        observation.putArray("warnings").addObject().put("fieldPath", "hints[0].description").put("category", "DELIVERY_TERMS")
                .put("errorKind", "MONETARY_VALUE").put("textSha256", h('i')).putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        return packet;
    }
    private Unit unit(ObjectNode packet) {
        return new Unit(UUID.randomUUID(),planId,packet.path("logicalKey").asText(),1,packet.path("ordinal").asInt(),
                Purpose.valueOf(packet.path("purpose").asText()),attemptId,h('j'),List.of(),UnitStatus.VERIFIED,"worker",1,now.plusSeconds(30),
                300000,7,packet.toString(),digest(packet.toString()),null,new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model",h('g')),
                packet.path("ordinal").asInt(),1,now,now);
    }
    private static String h(char c) { return String.format("%064x",(int)c); }
}
