package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutcomeUnknownException;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepProvenFailureException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DurableSemanticExecutionTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-16T00:00:00Z");
    final Parent parent = new Parent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "worker-test");
    final SemanticCheckpointCoordinator store = mock(SemanticCheckpointCoordinator.class);
    final SemanticWorkPort work = mock(SemanticWorkPort.class);
    final ContractTextAnalysisRequest request = new ContractTextAnalysisRequest(UUID.randomUUID(), List.of(
            new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "frozen-source")));
    final Runnable marker = mock(Runnable.class);
    final List<Unit> units = new ArrayList<>();
    Plan plan;

    DurableSemanticExecution fixture() throws Exception {
        var manifest = json.readTree("""
            {"version":2,"planFingerprint":"%s","inputFingerprint":"%s","policyFingerprint":"%s",
             "policy":{"model":"unit-model","provider":"OPENAI_COMPATIBLE","version":"contract-semantic-work-v3"},
             "timeLimitMs":900000,"callLimit":4,"repairLimit":2,
             "units":[{"logicalKey":"%s","ordinal":1,"purpose":"ANALYZE","inputFingerprint":"%s","dependencies":[]},
                      {"logicalKey":"%s","ordinal":2,"purpose":"SYNTHESIZE","inputFingerprint":"%s","dependencies":["%s"]}]}
            """.formatted(h('a'),h('b'),h('c'),h('d'),h('e'),h('f'),h('g'),h('d')));
        when(work.plan(request)).thenReturn(manifest);
        when(store.findPlan(parent.stepId())).thenReturn(Optional.empty());
        when(store.findUnits(any())).thenAnswer(call -> List.copyOf(units));
        when(store.openOrClaim(eq(parent), any(), anyList())).thenAnswer(call -> {
            plan = new Plan(UUID.randomUUID(), parent.runId(), parent.stepId(), parent.attemptId(), parent.attemptId(),
                    call.getArgument(1), parent.leaseOwner(), 1, now.plusSeconds(60), PlanStatus.ACTIVE,
                    0,0,0,0,0,0,null,0,now,now);
            return plan;
        });
        return new DurableSemanticExecution(store, work, json, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void observeCannotOpenOrSendWithoutItsLocalImmutableWindow() throws Exception {
        var service = fixture();
        var observe = new ContractTextAnalysisRequest(request.projectId(), request.materials(), null, "OBSERVE");
        assertThatThrownBy(() -> service.execute(observe, parent, h('z'), marker))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class, failure ->
                        assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_OBSERVE_WINDOW_INVALID"));
        verify(store, never()).openOrClaim(any(), any(), anyList());
        verify(work, never()).advance(any(), anyString(), anyList(), any(), anyLong(), anyBoolean());
    }

    @Test void observeDeadlineClipsToTheSameWindowAndTypedMetadataSurvivesFinalReplay() throws Exception {
        fixture();
        var observe = new ContractTextAnalysisRequest(request.projectId(), request.materials(), null, "OBSERVE");
        var manifest = work.plan(request).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("policy")).put("monetaryMode", "OBSERVE")
                .put("monetaryObservationProtocol", com.fuquan.audit.contract.application.ai.ContractMonetaryObservation.PROTOCOL);
        when(work.plan(observe)).thenReturn(manifest);
        var observation = new com.fuquan.audit.contract.application.ai.ContractMonetaryObservation(
                "contract-money-observation-v1", "OBSERVE", List.of(), List.of());
        when(store.openOrClaim(eq(parent), any(), anyList())).thenAnswer(call -> {
            plan = new Plan(UUID.randomUUID(),parent.runId(),parent.stepId(),parent.attemptId(),parent.attemptId(),call.getArgument(1),
                    parent.leaseOwner(),1,now.plusSeconds(10),PlanStatus.ACTIVE,2,0,0,0,14,0,null,0,now,now);
            for (var desc : List.of(descriptor(h('d'),h('e'),h('h'),"ANALYZE",1),descriptor(h('f'),h('g'),h('h'),"SYNTHESIZE",2))) {
                var packet = (com.fasterxml.jackson.databind.node.ObjectNode)checkpoint(desc);
                packet.set("monetaryObservation",json.valueToTree(observation));
                if ("SYNTHESIZE".equals(desc.path("purpose").asText()))
                    packet.putObject("finalResult").set("monetaryObservation",json.valueToTree(observation));
                units.add(unit(UnitStatus.VERIFIED,desc.path("logicalKey").asText(),Purpose.valueOf(desc.path("purpose").asText()),
                        desc.path("ordinal").asInt(),packet.toString()));
            }
            return plan;
        });
        var result = new ContractTextAnalysisResult(true,List.of("原综合结果"),List.of(),"unit-model",14,List.of(),observation);
        when(work.advance(eq(observe),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,null,result,null));
        var window = new SemanticObserveWindowPort.Window(UUID.randomUUID().toString(),now.minusSeconds(21580).toEpochMilli(),
                now.plusSeconds(20).toEpochMilli(),h('w'));
        var service = new DurableSemanticExecution(store,work,json,Clock.fixed(now,ZoneOffset.UTC),SemanticCheckpointBoundary.NONE, ignored -> window);
        assertThat(service.execute(observe,parent,h('z'),marker).monetaryObservation()).isEqualTo(observation);
        assertThat(plan.spec().deadlineAt()).isEqualTo(now.plusSeconds(20));
        assertThat(json.readTree(plan.spec().planJson()).path("observeWindow").path("sha256").asText()).isEqualTo(h('w'));
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),eq(true));
    }

    @Test void changedWindowIsRejectedBeforeReservingOrSendingTheNextOperation() throws Exception {
        fixture();
        var observe = new ContractTextAnalysisRequest(request.projectId(),request.materials(),null,"OBSERVE");
        var manifest = work.plan(request).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("policy")).put("monetaryMode","OBSERVE")
                .put("monetaryObservationProtocol","contract-money-observation-v1");
        when(work.plan(observe)).thenReturn(manifest);
        when(work.advance(eq(observe),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,descriptor(h('d'),h('e'),h('h'),"ANALYZE",1),null,null));
        var window = mock(SemanticObserveWindowPort.class);
        when(window.read(now)).thenReturn(new SemanticObserveWindowPort.Window(UUID.randomUUID().toString(),
                now.minusSeconds(21580).toEpochMilli(),now.plusSeconds(20).toEpochMilli(),h('w')))
                .thenThrow(new IllegalStateException("window changed"));
        var service = new DurableSemanticExecution(store,work,json,Clock.fixed(now,ZoneOffset.UTC),SemanticCheckpointBoundary.NONE,window);
        assertThatThrownBy(() -> service.execute(observe,parent,h('z'),marker)).isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                failure -> assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_OBSERVE_WINDOW_INVALID"));
        verify(store,never()).reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any());
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),eq(true));
    }

    @Test void strictMonetaryRepairRequiredPacketKeepsItsFailedCallAuditBeforeTheExplicitRepairDispatch() throws Exception {
        var service = fixture();
        var first = descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        var repair = descriptor(h('d'),h('j'),h('i'),"REPAIR",1);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,first,null,null));
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> unit(UnitStatus.SENT,call.getArgument(1),call.getArgument(2),1,null));
        var packet = (com.fasterxml.jackson.databind.node.ObjectNode)checkpoint(first);
        packet.put("status","REPAIR_REQUIRED");
        ((com.fasterxml.jackson.databind.node.ObjectNode)packet.path("call")).put("outcome","FAILED").put("safeReason","MODEL_INVALID_RESULT");
        var observation = packet.putObject("monetaryObservation").put("protocol","contract-money-observation-v1").put("mode","STRICT");
        observation.putArray("warnings").addObject().put("fieldPath","hints[0].description").putNull("category")
                .put("errorKind","MONETARY_VALUE").put("textSha256",h('m')).putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        when(work.advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(packet,repair,null,null));
        when(store.completeUnit(any(),any(),any(),anyString(),anyString(),anyLong(),any(),any()))
                .thenAnswer(call -> {
                    var value=unit(UnitStatus.REPAIR_REQUIRED,h('d'),Purpose.ANALYZE,1,call.getArgument(3)); units.add(value); return value;
                });
        when(work.advance(eq(request),anyString(),anyList(),eq(h('i')),anyLong(),eq(true)))
                .thenThrow(new IllegalStateException("synthetic uncertain repair transport"));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker)).isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(store).completeUnit(any(),any(),eq(UnitStatus.REPAIR_REQUIRED),eq(packet.toString()),eq(digest(packet.toString())),
                eq(7L),any(),eq("MODEL_INVALID_RESULT"));
        verify(store).reserveAndMarkSent(any(),eq(h('d')),eq(Purpose.REPAIR),eq(h('j')),anyLong(),any());
        assertThat(units).singleElement().satisfies(value -> assertThat(value.resultJson()).contains("ABSOLUTE_VALUE", "MONETARY_VALUE"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"contract-semantic-work-v2", "contract-semantic-work-v4", ""})
    void currentDurableRuntimeRejectsAnUnmatchedPythonPolicyBeforeOpeningOrSending(String pythonPolicy) throws Exception {
        var service = fixture();
        var manifest = work.plan(request).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("policy")).put("version", pythonPolicy);
        when(work.plan(request)).thenReturn(manifest);

        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class, failure ->
                        assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_POLICY_INCOMPATIBLE"));

        verify(store,never()).openOrClaim(any(),any(),anyList());
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),anyBoolean());
        verifyNoInteractions(marker);
    }

    @Test void unknownPersistentDispatchNeverCallsDescribeOrProviderAgain() throws Exception {
        var service = fixture();
        when(store.openOrClaim(eq(parent), any(), anyList())).thenAnswer(call -> {
            plan = new Plan(UUID.randomUUID(), parent.runId(), parent.stepId(), parent.attemptId(), parent.attemptId(),
                    call.getArgument(1), parent.leaseOwner(), 1, now.plusSeconds(60), PlanStatus.ACTIVE,
                    0,1,0,0,0,100,null,0,now,now);
            units.add(unit(UnitStatus.UNKNOWN, h('d'), Purpose.ANALYZE, 1, null));
            return plan;
        });
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(work, never()).advance(any(), anyString(), anyList(), any(), anyLong(), anyBoolean());
        verify(store, never()).reserveAndMarkSent(any(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(marker);
    }

    @Test void outboundAndAuditCheckpointAreDurableBeforeNextUnitDispatch() throws Exception {
        fixture();
        var boundary=mock(SemanticCheckpointBoundary.class);
        var service=new DurableSemanticExecution(store,work,json,Clock.fixed(now,ZoneOffset.UTC),boundary);
        var first = descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        var last = descriptor(h('f'),h('i'),h('j'),"SYNTHESIZE",2);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,first,null,null));
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> unit(UnitStatus.SENT,call.getArgument(1),call.getArgument(2),
                        h('d').equals(call.getArgument(1)) ? 1 : 2,null));
        var checkpointA = checkpoint(first);
        var checkpointS = checkpoint(last);
        var result = new ContractTextAnalysisResult(true,List.of("validated"),List.of(),"unit-model",14);
        when(work.advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(checkpointA,last,null,null));
        when(work.advance(eq(request),anyString(),anyList(),eq(h('j')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(checkpointS,null,result,null));
        when(store.completeUnit(any(),any(),any(),anyString(),anyString(),anyLong(),any(),any()))
                .thenAnswer(call -> {
                    var node=json.readTree((String)call.getArgument(3));
                    var value=unit(UnitStatus.VERIFIED,node.path("logicalKey").asText(),
                            Purpose.valueOf(node.path("purpose").asText()),node.path("ordinal").asInt(),call.getArgument(3));
                    units.add(value); return value;
                });
        assertThat(service.execute(request,parent,h('z'),marker).available()).isTrue();
        var order=inOrder(marker,store,work,boundary);
        order.verify(marker).run();
        order.verify(store).reserveAndMarkSent(any(),eq(h('d')),eq(Purpose.ANALYZE),eq(h('e')),anyLong(),any());
        order.verify(work).advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true));
        order.verify(store).completeUnit(any(),any(),eq(UnitStatus.VERIFIED),anyString(),anyString(),eq(7L),any(),isNull());
        order.verify(boundary).afterCommit(any(),argThat(saved -> saved.size()==1 && saved.get(0).status()==UnitStatus.VERIFIED));
        order.verify(store).reserveAndMarkSent(any(),eq(h('f')),eq(Purpose.SYNTHESIZE),eq(h('g')),anyLong(),any());
        order.verify(work).advance(eq(request),anyString(),anyList(),eq(h('j')),anyLong(),eq(true));
        order.verify(store).completeUnit(any(),any(),eq(UnitStatus.VERIFIED),anyString(),anyString(),eq(7L),any(),isNull());
        order.verify(boundary).afterCommit(any(),argThat(saved -> saved.size()==2));
        order.verify(store).markComplete(any(),anyString());
    }

    @Test void transportFailureAfterSentIsUnknownNotOrdinaryFailureOrAutomaticRetry() throws Exception {
        var service=fixture(); var next=descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,next,null,null));
        var identity=UUID.randomUUID();
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> new Unit(identity,plan.planId(),h('d'),1,1,Purpose.ANALYZE,parent.attemptId(),
                        h('e'),List.of(),UnitStatus.SENT,parent.leaseOwner(),1,now.plusSeconds(60),100,0,null,null,null,
                        new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model",h('h')),1,0,now,now));
        when(work.advance(eq(request),anyString(),anyList(),anyString(),anyLong(),eq(true)))
                .thenThrow(new IllegalStateException("safe simulated transport failure"));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(store).markUnknown(any(),eq(identity),eq("TRANSPORT_OUTCOME_UNKNOWN"));
        verify(work,times(1)).advance(eq(request),anyString(),anyList(),anyString(),anyLong(),eq(true));
        verify(store,never()).markComplete(any(),anyString());
    }

    @Test void nextPhaseBoundPreservesVerifiedAnalysisAndItsPreciseFailureReason() throws Exception {
        var service=fixture(); var next=descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,next,null,null));
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> unit(UnitStatus.SENT,h('d'),Purpose.ANALYZE,1,null));
        when(work.advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(checkpoint(next),null,null,"SEMANTIC_SYNTHESIS_INPUT_BOUND"));
        when(store.completeUnit(any(),any(),any(),anyString(),anyString(),anyLong(),any(),any()))
                .thenAnswer(call -> unit(UnitStatus.VERIFIED,h('d'),Purpose.ANALYZE,1,call.getArgument(3)));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepProvenFailureException.class)
                .extracting("safeReasonCode").isEqualTo("SEMANTIC_SYNTHESIS_INPUT_BOUND");
        verify(store).completeUnit(any(),any(),eq(UnitStatus.VERIFIED),anyString(),anyString(),eq(7L),any(),isNull());
        verify(store,never()).markUnknown(any(),any(),anyString());
        verify(store,never()).markComplete(any(),anyString());
        verify(work,times(1)).advance(eq(request),anyString(),anyList(),anyString(),anyLong(),eq(true));
    }

    @Test void historicalFailedExecutionDoesNotHideItsLaterVerifiedExplicitRetryOnReopen() throws Exception {
        var service=fixture();
        var saved=checkpoint(descriptor(h('d'),h('e'),h('h'),"ANALYZE",1));
        when(store.findUnits(any())).thenAnswer(call -> {
            if(units.isEmpty()) {
                var failed=unit(UnitStatus.FAILED,h('d'),Purpose.ANALYZE,1,null);
                units.add(failed);
                var verified=unit(UnitStatus.VERIFIED,h('d'),Purpose.REPAIR,1,json.writeValueAsString(saved));
                units.add(new Unit(verified.executionId(),verified.planId(),verified.logicalKey(),2,verified.ordinal(),
                        verified.purpose(),verified.attemptId(),verified.inputFingerprint(),verified.dependencies(),verified.status(),
                        verified.owner(),verified.fence(),verified.leaseUntil(),verified.reservedMs(),verified.durationMs(),
                        verified.resultJson(),verified.resultSha256(),verified.safeReason(),verified.call(),verified.callOrdinal(),
                        verified.versionNo(),verified.createdAt(),verified.updatedAt()));
            }
            return List.copyOf(units);
        });
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,null,null,"MODEL_INVALID_RESULT"));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepProvenFailureException.class);
        var prefix=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(work).advance(eq(request),anyString(),prefix.capture(),isNull(),anyLong(),eq(false));
        assertThat(prefix.getValue()).containsExactly(saved);
        verifyNoInteractions(marker);
        verify(store,never()).reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=UnitStatus.class,names={"SENT","UNKNOWN"})
    void persistedUncertainDispatchDominatesAnUnavailableOrChangedPythonPlanner(UnitStatus status) throws Exception {
        var service=fixture();
        var spec=new PlanSpec(h('b'),h('c'),"{}",now.plusSeconds(900),900000,4,2);
        plan=new Plan(UUID.randomUUID(),parent.runId(),parent.stepId(),parent.attemptId(),parent.attemptId(),spec,
                parent.leaseOwner(),1,now.plusSeconds(60),PlanStatus.ACTIVE,0,1,0,0,0,100,null,0,now,now);
        units.add(unit(status,h('d'),Purpose.ANALYZE,1,null));
        units.add(unit(UnitStatus.PLANNED,h('f'),Purpose.SYNTHESIZE,2,null));
        when(store.findPlan(parent.stepId())).thenReturn(Optional.of(plan));
        when(store.openOrClaim(eq(parent),eq(spec),anyList())).thenReturn(plan);
        when(work.plan(request)).thenThrow(new IllegalStateException("planner unavailable"));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(store).openOrClaim(eq(parent),eq(spec),anyList());
        verify(work,never()).plan(any());
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),anyBoolean());
        verifyNoInteractions(marker);
    }

    @Test void completePlanCanPublishAfterDeadlineWithoutReservingOrSendingAnotherCall() throws Exception {
        var service=fixture();
        var manifest=work.plan(request);
        var saved=json.createObjectNode().put("frozenInputIdentity",h('z')).set("pythonManifest",manifest);
        var spec=new PlanSpec(digest(h('z')+"\n"+h('b')),h('c'),json.writeValueAsString(saved),now.minusSeconds(1),900000,4,2);
        plan=new Plan(UUID.randomUUID(),parent.runId(),parent.stepId(),parent.attemptId(),parent.attemptId(),spec,
                parent.leaseOwner(),1,now.plusSeconds(60),PlanStatus.COMPLETE,2,0,0,0,14,0,h('p'),0,now,now);
        units.add(unit(UnitStatus.VERIFIED,h('d'),Purpose.ANALYZE,1,json.writeValueAsString(checkpoint(descriptor(h('d'),h('e'),h('h'),"ANALYZE",1)))));
        units.add(unit(UnitStatus.VERIFIED,h('f'),Purpose.SYNTHESIZE,2,json.writeValueAsString(checkpoint(descriptor(h('f'),h('i'),h('j'),"SYNTHESIZE",2)))));
        when(store.findPlan(parent.stepId())).thenReturn(Optional.of(plan));
        when(store.openOrClaim(eq(parent),eq(spec),anyList())).thenReturn(plan);
        var result=new ContractTextAnalysisResult(true,List.of("verified-before-deadline"),List.of(),"unit-model",14);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,null,result,null));
        assertThat(service.execute(request,parent,h('z'),marker).available()).isTrue();
        verify(store,never()).reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any());
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),eq(true));
        verifyNoInteractions(marker);
    }

    @Test void completedObservePlanCanPublishAfterTheWindowExpiresUsingTheExactSavedProofAndNoNewCall(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        fixture();
        var observe = new ContractTextAnalysisRequest(request.projectId(),request.materials(),null,"OBSERVE");
        var manifest = work.plan(request).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("policy")).put("monetaryMode","OBSERVE")
                .put("monetaryObservationProtocol","contract-money-observation-v1");
        when(work.plan(observe)).thenReturn(manifest);
        String windowId = UUID.randomUUID().toString();
        String proof = """
            {"kind":"LOCAL_V2_OBSERVE_WINDOW_V1","windowId":"%s","startedAtEpochMs":%d,"expiresAtEpochMs":%d,
             "maximumActualCalls":200,"startSemantics":"CONSERVATIVE_PRE_RUN_POST","initialCandidateSha":"%s"}
            """.formatted(windowId,now.minusSeconds(21601).toEpochMilli(),now.minusSeconds(1).toEpochMilli(),"a".repeat(40));
        var file = directory.resolve("window.json"); java.nio.file.Files.writeString(file,proof);
        var window = new SemanticObserveWindowPort.Window(windowId,now.minusSeconds(21601).toEpochMilli(),
                now.minusSeconds(1).toEpochMilli(),digest(proof));
        var observation = new com.fuquan.audit.contract.application.ai.ContractMonetaryObservation(
                "contract-money-observation-v1","OBSERVE",List.of(),List.of());
        var saved = json.createObjectNode().put("frozenInputIdentity",h('z'));
        saved.set("pythonManifest",manifest); saved.set("observeWindow",json.valueToTree(window));
        var spec = new PlanSpec(digest(h('z')+"\n"+h('b')),h('c'),saved.toString(),now.minusSeconds(1),900000,4,2);
        plan = new Plan(UUID.randomUUID(),parent.runId(),parent.stepId(),parent.attemptId(),parent.attemptId(),spec,
                parent.leaseOwner(),1,now.plusSeconds(60),PlanStatus.COMPLETE,2,0,0,0,14,0,h('p'),0,now,now);
        for (var desc : List.of(descriptor(h('d'),h('e'),h('h'),"ANALYZE",1),descriptor(h('f'),h('g'),h('h'),"SYNTHESIZE",2))) {
            var packet = (com.fasterxml.jackson.databind.node.ObjectNode)checkpoint(desc);
            packet.set("monetaryObservation",json.valueToTree(observation));
            if ("SYNTHESIZE".equals(desc.path("purpose").asText()))
                packet.putObject("finalResult").set("monetaryObservation",json.valueToTree(observation));
            units.add(unit(UnitStatus.VERIFIED,desc.path("logicalKey").asText(),Purpose.valueOf(desc.path("purpose").asText()),
                    desc.path("ordinal").asInt(),packet.toString()));
        }
        when(store.findPlan(parent.stepId())).thenReturn(Optional.of(plan));
        when(store.openOrClaim(eq(parent),eq(spec),anyList())).thenReturn(plan);
        var result = new ContractTextAnalysisResult(true,List.of("窗口内完成的结果"),List.of(),"unit-model",14,List.of(),observation);
        when(work.advance(eq(observe),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,null,result,null));
        var service = new DurableSemanticExecution(store,work,json,Clock.fixed(now,ZoneOffset.UTC),SemanticCheckpointBoundary.NONE,
                new com.fuquan.audit.integration.file.LocalSemanticObserveWindow(file,json));
        assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.execute(observe,parent,h('z'),marker))
                .monetaryObservation()).isEqualTo(observation);
        verify(store,never()).reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any());
        verify(work,never()).advance(any(),anyString(),anyList(),any(),anyLong(),eq(true));
        verifyNoInteractions(marker);
    }

    @Test void observedUnknownRetainsMeasuredDurationAndUsageInsteadOfCrashDefaults() throws Exception {
        var service=fixture(); var next=descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,next,null,null));
        var identity=UUID.randomUUID();
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> new Unit(identity,plan.planId(),h('d'),1,1,Purpose.ANALYZE,parent.attemptId(),h('e'),
                        List.of(),UnitStatus.SENT,parent.leaseOwner(),1,now.plusSeconds(60),300000,0,null,null,null,
                        new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model",h('h')),1,0,now,now));
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)checkpoint(next);
        response.put("status","UNKNOWN");
        var call=(com.fasterxml.jackson.databind.node.ObjectNode)response.path("call");
        call.put("outcome","FAILED");call.put("safeReason","TRANSPORT_OUTCOME_UNKNOWN");call.put("durationMs",120001);
        call.put("finishReason","UNKNOWN");call.put("promptTokens",101);call.put("completionTokens",2);call.put("totalTokens",103);
        when(work.advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(response,null,null,null));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker)).isInstanceOf(ReviewStepOutcomeUnknownException.class);
        verify(store).markUnknown(any(),eq(identity),eq("TRANSPORT_OUTCOME_UNKNOWN"),eq(120001L),
                eq(new CallUsage("UNKNOWN",101,2,103)));
        verify(store,never()).markUnknown(any(),any(),anyString());
        verify(store,never()).markComplete(any(),anyString());
    }

    @Test void localCheckpointBoundPersistsActualCallUsageAndExactProvenFailure() throws Exception {
        var service=fixture(); var next=descriptor(h('d'),h('e'),h('h'),"ANALYZE",1);
        when(work.advance(eq(request),anyString(),anyList(),isNull(),anyLong(),eq(false)))
                .thenReturn(new SemanticWorkPort.Response(null,next,null,null));
        when(store.reserveAndMarkSent(any(),anyString(),any(),anyString(),anyLong(),any()))
                .thenAnswer(call -> unit(UnitStatus.SENT,h('d'),Purpose.ANALYZE,1,null));
        var packet=(com.fasterxml.jackson.databind.node.ObjectNode)checkpoint(next);
        packet.put("status","FAILED");
        var call=(com.fasterxml.jackson.databind.node.ObjectNode)packet.path("call");
        call.put("outcome","FAILED"); call.put("safeReason","SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        call.put("durationMs",1234); call.put("promptTokens",100); call.put("completionTokens",200); call.put("totalTokens",300);
        when(work.advance(eq(request),anyString(),anyList(),eq(h('h')),anyLong(),eq(true)))
                .thenReturn(new SemanticWorkPort.Response(packet,null,null,"SEMANTIC_CHECKPOINT_OUTPUT_BOUND"));
        assertThatThrownBy(() -> service.execute(request,parent,h('z'),marker))
                .isInstanceOf(ReviewStepProvenFailureException.class)
                .extracting("safeReasonCode").isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        verify(store).completeUnit(any(),any(),eq(UnitStatus.FAILED),eq(json.writeValueAsString(packet)),
                eq(digest(json.writeValueAsString(packet))),eq(1234L),eq(new CallUsage("STOP",100,200,300)),
                eq("SEMANTIC_CHECKPOINT_OUTPUT_BOUND"));
        verify(store,never()).markUnknown(any(),any(),anyString());
        verify(store,never()).markComplete(any(),anyString());
        verify(work,times(1)).advance(eq(request),anyString(),anyList(),anyString(),anyLong(),eq(true));
    }

    JsonNode descriptor(String key,String input,String operation,String purpose,int ordinal) {
        var node=json.createObjectNode(); node.put("logicalKey",key);node.put("inputFingerprint",input);
        node.put("operationFingerprint",operation);node.put("purpose",purpose);node.put("ordinal",ordinal);return node;
    }
    JsonNode checkpoint(JsonNode descriptor) {
        var node=descriptor.deepCopy(); var value=(com.fasterxml.jackson.databind.node.ObjectNode)node;
        value.put("status","VERIFIED");value.put("checkpointHash",h('k'));
        value.set("call",json.valueToTree(Map.of("outcome","SUCCEEDED","provider","OPENAI_COMPATIBLE",
                "model","unit-model","durationMs",7,"finishReason","STOP")));
        return node;
    }
    Unit unit(UnitStatus status,String key,Purpose purpose,int ordinal,String result) {
        return new Unit(UUID.randomUUID(),plan.planId(),key,1,ordinal,purpose,parent.attemptId(),h('e'),
                purpose==Purpose.SYNTHESIZE?List.of(h('d')):List.of(),status,
                parent.leaseOwner(),1,now.plusSeconds(60),100,7,result,result==null?null:digest(result),
                status==UnitStatus.UNKNOWN?"TRANSPORT_OUTCOME_UNKNOWN":null,
                new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model",h('h')),ordinal,0,now,now);
    }
    static String h(char c) { return String.format("%064x",(int)c); }
}
