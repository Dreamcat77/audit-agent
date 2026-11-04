package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticHierarchyCheckpointTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticHierarchyResultAuthorityTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant now = SemanticCheckpointCoordinatorTest.NOW;
    final UUID runId = UUID.randomUUID(), stepId = UUID.randomUUID(), attemptId = UUID.randomUUID(), planId = UUID.randomUUID();
    final String policy = "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1";
    final Parent parent = new Parent(runId, stepId, attemptId, "worker");
    final SemanticCheckpointRepository repository = mock(SemanticCheckpointRepository.class);
    final SemanticCheckpointCoordinator coordinator = mock(SemanticCheckpointCoordinator.class);
    final SemanticWorkPort work = mock(SemanticWorkPort.class);
    final List<Unit> units = new ArrayList<>();
    final ObjectNode python = manifest(1);
    final SemanticObserveWindowPort.Window window = new SemanticObserveWindowPort.Window(UUID.randomUUID().toString(),
            now.minusSeconds(60).toEpochMilli(), now.plusSeconds(21540).toEpochMilli(), key("window"));
    final ContractReviewRun run = new ContractReviewRun(runId, UUID.randomUUID(), UUID.randomUUID(), 0, null, null, 1,
            "{}", INPUT, "profile", "rule", policy, INPUT, "tester", ContractReviewRunStatus.COMPLETED,
            now, now, now, null, null, null, 1);
    final ContractReviewStep step = new ContractReviewStep(stepId, runId, ContractReviewStepType.SEMANTIC_AI, true,
            ContractReviewStepStatus.COMPLETED, policy, 1, "REVIEW_FINDING_SET", attemptId, null, now, now, 1);
    final ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId, runId, stepId, 1, key("attempt"),
            ContractReviewStepStatus.COMPLETED, null, null, now, ReviewStepExternalCallState.REQUESTED,
            INPUT, null, "OPENAI_COMPATIBLE", "synthetic", now, now, null, "REVIEW_FINDING_SET", attemptId, 1);
    final ContractReviewRunCreateResult frozen = new ContractReviewRunCreateResult(run, List.of(), List.of(step), List.of(attempt), false);
    final ReviewStepOutputReadService.StepOutput output = new ReviewStepOutputReadService.StepOutput(run, step,
            List.of(attempt), null, List.of(), List.of(), List.of());
    ObjectNode groupPacket;
    Plan plan;
    ContractMonetaryObservation observation;

    void fixture() {
        python.put("inputFingerprint", key("input")); seal();
        units.add(unit(packet("A1", 1, "ANALYZE", "ANALYSIS", "VERIFIED"), 1));
        units.add(unit(packet("G1", 2, "SYNTHESIZE", "GROUP", "REPAIR_REQUIRED"), 1));
        groupPacket = packet("G1", 2, "REPAIR", "GROUP", "VERIFIED");
        ((ObjectNode)groupPacket.path("monetaryObservation")).putArray("warnings").addObject()
                .put("fieldPath", "summary").putNull("category").put("errorKind", "MONETARY_VALUE")
                .put("textSha256", key("original-complete-text")).putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        units.add(unit(groupPacket, 2));
        observation = new ContractMonetaryObservation("contract-money-observation-v1", "OBSERVE", List.of(),
                SemanticMonetaryObservationVerifier.workWarnings(units, "OBSERVE", json));
        var last = packet("F", 3, "SYNTHESIZE", "FINAL", "VERIFIED");
        var result = last.putObject("finalResult"); result.putArray("hints");
        result.set("monetaryObservation", json.valueToTree(observation));
        units.add(unit(last, 1)); replacePlan();
        when(repository.findPlanByStep(stepId)).thenAnswer(call -> Optional.of(plan));
        when(repository.findUnits(planId)).thenAnswer(call -> List.copyOf(units));
        when(coordinator.findPlan(stepId)).thenAnswer(call -> Optional.of(plan));
        when(coordinator.findUnits(planId)).thenAnswer(call -> List.copyOf(units));
        when(coordinator.openOrClaim(eq(parent), any(), anyList())).thenAnswer(call -> plan);
        when(coordinator.markComplete(any(), anyString())).thenAnswer(call -> plan);
    }
    void seal() {
        python.remove("planFingerprint");
        python.put("policyFingerprint", SemanticMonetaryObservationVerifier.canonicalHash(python.path("policy")));
        python.put("planFingerprint", SemanticMonetaryObservationVerifier.canonicalHash(python));
    }
    void replacePlan() {
        var envelope = json.createObjectNode().put("frozenInputIdentity", INPUT);
        envelope.set("pythonManifest", python); envelope.set("observeWindow", json.valueToTree(window));
        var spec = new PlanSpec(digest(INPUT + "\n" + python.path("inputFingerprint").asText()),
                python.path("policyFingerprint").asText(), envelope.toString(), now.plusSeconds(900), 900000, 5, 2);
        plan = new Plan(planId, runId, stepId, attemptId, attemptId, spec, "worker", 1, now.plusSeconds(30),
                PlanStatus.COMPLETE, 4, 0, 1, 0, 28, 0, units.get(3).resultSha256(), 1, now, now);
    }
    ObjectNode packet(String stage, int ordinal, String purpose, String role, String status) {
        var p = json.createObjectNode().put("logicalKey", key(stage)).put("ordinal", ordinal).put("purpose", purpose)
                .put("role", role).put("operationFingerprint", key("operation-" + stage + purpose))
                .put("checkpointHash", key("checkpoint-" + stage + purpose)).put("status", status);
        var o = p.putObject("monetaryObservation").put("protocol", "contract-money-observation-v1").put("mode", "OBSERVE");
        o.putArray("warnings"); o.putArray("workWarnings");
        return p;
    }
    Unit unit(ObjectNode p, int executionNo) {
        return new Unit(UUID.randomUUID(), planId, p.path("logicalKey").asText(), executionNo, p.path("ordinal").asInt(),
                Purpose.valueOf(p.path("purpose").asText()), attemptId, INPUT, List.of(), UnitStatus.valueOf(p.path("status").asText()),
                "worker", 1, now.plusSeconds(30), 300000, 7, p.toString(), digest(p.toString()), null,
                new CallMetadata(UUID.randomUUID(), "OPENAI_COMPATIBLE", "synthetic", p.path("operationFingerprint").asText()),
                p.path("ordinal").asInt(), 1, now, now);
    }

    @Test void readerSelectsFrozenFinalKeyNotTheGroupRepairWithLargerExecutionNumber() {
        fixture(); var reader = new SemanticMonetaryObservationReader(repository, json);
        assertThat(reader.read(frozen, List.of(output))).isEqualTo(observation);
        assertThat(observation.workWarnings()).singleElement().satisfies(w -> {
            assertThat(w.logicalKey()).isEqualTo(key("G1")); assertThat(w.purpose()).isEqualTo("REPAIR");
        });
        verifyNoInteractions(work, coordinator);
    }

    @Test void executionReopensCompleteFinalWithoutCallingGroupOrFinalAgain() throws Exception {
        fixture();
        var wire = (ObjectNode)json.valueToTree(new ContractTextAnalysisRequest(run.projectId(),
                List.of(new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "frozen source")), "contract-semantic-quality-v1", "OBSERVE"));
        wire.put("semanticHierarchyProtocol", "contract-bounded-hierarchy-v1");
        var request = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> json.treeToValue(wire, ContractTextAnalysisRequest.class));
        when(work.plan(request)).thenReturn(python);
        var result = new ContractTextAnalysisResult(true, List.of("final source-owned opinion"), List.of(), "synthetic", 28, List.of(), observation);
        when(work.advance(eq(request), anyString(), anyList(), isNull(), anyLong(), eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null, null, result, null));
        var marker = mock(Runnable.class);
        var service = new DurableSemanticExecution(coordinator, work, json, Clock.fixed(now, ZoneOffset.UTC), SemanticCheckpointBoundary.NONE, ignored -> window);
        assertThat(service.execute(request, parent, INPUT, marker)).isEqualTo(result);
        verify(coordinator).markComplete(any(), eq(units.get(3).resultSha256()));
        verify(coordinator, never()).reserveAndMarkSent(any(), anyString(), any(), anyString(), anyLong(), any());
        verify(work, never()).advance(any(), anyString(), anyList(), any(), anyLong(), eq(true)); verifyNoInteractions(marker);
    }

    @Test void groupCannotCarryFinalResultEvenWhenItsRowHashIsValid() {
        fixture(); groupPacket.putObject("finalResult").putArray("hints"); units.set(2, unit(groupPacket, 2));
        assertThatThrownBy(() -> new SemanticMonetaryObservationReader(repository, json).read(frozen, List.of(output)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void forgedFinalKeyCannotBorrowGroupAuthorityEvenAfterManifestIsResigned() {
        fixture(); ((ObjectNode)python.path("hierarchy")).put("finalLogicalKey", key("G1")); seal(); replacePlan();
        assertThatThrownBy(() -> new SemanticMonetaryObservationReader(repository, json).read(frozen, List.of(output)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void replayRejectsStoredGroupFinalBodyBeforeValidationOrPublication() throws Exception {
        fixture(); groupPacket.putObject("finalResult").putArray("hints"); units.set(2, unit(groupPacket, 2));
        var wire = (ObjectNode)json.valueToTree(new ContractTextAnalysisRequest(run.projectId(),
                List.of(new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "frozen source")), "contract-semantic-quality-v1", "OBSERVE"));
        wire.put("semanticHierarchyProtocol", "contract-bounded-hierarchy-v1");
        var request = json.treeToValue(wire, ContractTextAnalysisRequest.class); when(work.plan(request)).thenReturn(python);
        var service = new DurableSemanticExecution(coordinator, work, json, Clock.fixed(now, ZoneOffset.UTC), SemanticCheckpointBoundary.NONE, ignored -> window);
        assertThatThrownBy(() -> service.execute(request, parent, INPUT, () -> {})).isInstanceOf(ReviewStepProvenFailureException.class);
        verify(work, never()).advance(any(), anyString(), anyList(), any(), anyLong(), anyBoolean());
        verify(coordinator, never()).markComplete(any(), anyString());
    }
}
