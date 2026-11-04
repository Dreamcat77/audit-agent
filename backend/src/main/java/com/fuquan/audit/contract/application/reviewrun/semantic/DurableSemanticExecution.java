package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutcomeUnknownException;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepProvenFailureException;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;

public final class DurableSemanticExecution {
    private final SemanticCheckpointCoordinator checkpoints;
    private final SemanticWorkPort work;
    private final ObjectMapper json;
    private final Clock clock;
    private final SemanticCheckpointBoundary boundary;
    private final SemanticObserveWindowPort observeWindow;
    public DurableSemanticExecution(SemanticCheckpointCoordinator checkpoints, SemanticWorkPort work,
            ObjectMapper json, Clock clock) {
        this(checkpoints,work,json,clock,SemanticCheckpointBoundary.NONE);
    }
    public DurableSemanticExecution(SemanticCheckpointCoordinator checkpoints, SemanticWorkPort work,
            ObjectMapper json, Clock clock, SemanticCheckpointBoundary boundary) {
        this(checkpoints, work, json, clock, boundary, null);
    }
    public DurableSemanticExecution(SemanticCheckpointCoordinator checkpoints, SemanticWorkPort work,
            ObjectMapper json, Clock clock, SemanticCheckpointBoundary boundary, SemanticObserveWindowPort observeWindow) {
        this.checkpoints=Objects.requireNonNull(checkpoints); this.work=Objects.requireNonNull(work);
        this.json=Objects.requireNonNull(json); this.clock=Objects.requireNonNull(clock);
        this.boundary=Objects.requireNonNull(boundary);
        this.observeWindow=observeWindow;
    }
    public ContractTextAnalysisResult execute(ContractTextAnalysisRequest request, Parent parent,
            String frozenInputIdentity, Runnable markExternalRequested) {
        hash(frozenInputIdentity);
        // Durable uncertainty dominates remote availability and policy drift.
        // Rediscover/close the old dispatch before asking Python to plan again.
        Plan previous=checkpoints.findPlan(parent.stepId()).orElse(null);
        if (previous!=null) {
            List<Unit> recorded=checkpoints.findUnits(previous.planId());
            if (recorded.stream().anyMatch(u -> u.status()==UnitStatus.SENT || u.status()==UnitStatus.UNKNOWN)) {
                try {
                    var initial=recorded.stream().filter(u -> u.executionNo()==1)
                            .sorted(Comparator.comparingInt(Unit::ordinal))
                            .map(u -> new UnitSpec(u.logicalKey(),u.ordinal(),u.purpose(),u.inputFingerprint(),u.dependencies())).toList();
                    // This commits SENT -> UNKNOWN plus its actual old Attempt
                    // audit while the reclaimed parent lease is still RUNNING.
                    checkpoints.openOrClaim(parent,previous.spec(),initial);
                } catch (RuntimeException unproven) {
                    // Unknown storage/ownership cannot become an ordinary failure
                    // or permission to resend. Existing reservations stay held.
                }
                throw unknown();
            }
        }
        SemanticObserveWindowPort.Window window = window(request,previous != null && previous.status() == PlanStatus.COMPLETE);
        JsonNode manifest;
        try { manifest=work.plan(request).deepCopy(); }
        catch (RuntimeException failure) { throw failed("SEMANTIC_PLAN_UNAVAILABLE"); }
        validateManifest(manifest);
        final String finalKey;
        try { finalKey = SemanticWorkManifest.finalLogicalKey(manifest, request.semanticHierarchyProtocol()); }
        catch (IllegalArgumentException invalid) { throw failed("SEMANTIC_PLAN_INVALID"); }
        SemanticMonetaryObservationVerifier.validatePolicy(manifest.path("policy"), request.semanticMoneyMode());
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).remove("next");
        List<UnitSpec> specs=new ArrayList<>();
        for (JsonNode unit:manifest.path("units")) {
            var dependencies=new ArrayList<String>();
            unit.path("dependencies").forEach(value -> dependencies.add(value.asText()));
            specs.add(new UnitSpec(requiredHash(unit,"logicalKey"),unit.path("ordinal").asInt(),
                    Purpose.valueOf(unit.path("purpose").asText()),requiredHash(unit,"inputFingerprint"),dependencies));
        }
        var envelope=json.createObjectNode().put("frozenInputIdentity",frozenInputIdentity);
        envelope.set("pythonManifest",manifest);
        if (window != null) envelope.set("observeWindow",json.valueToTree(window));
        String serialized=write(envelope);
        String inputFingerprint=digest(frozenInputIdentity+"\n"+requiredHash(manifest,"inputFingerprint"));
        PlanSpec spec;
        if (previous!=null) {
            spec=previous.spec();
            if (!spec.inputFingerprint().equals(inputFingerprint)
                    || !spec.policyFingerprint().equals(requiredHash(manifest,"policyFingerprint"))
                    || !read(spec.planJson()).equals(read(serialized))) throw failed("SEMANTIC_POLICY_INCOMPATIBLE");
        } else {
            long limit=manifest.path("timeLimitMs").asLong();
            var deadline=clock.instant().plusMillis(limit);
            if (window != null && window.expiresAt().isBefore(deadline)) deadline=window.expiresAt();
            spec=new PlanSpec(inputFingerprint,requiredHash(manifest,"policyFingerprint"),serialized,
                    deadline,limit,manifest.path("callLimit").asInt(),manifest.path("repairLimit").asInt());
        }
        Plan plan=checkpoints.openOrClaim(parent,spec,specs);
        Access access=new Access(parent,plan.planId(),plan.fence());
        List<JsonNode> prefix=prefix(checkpoints.findUnits(plan.planId()), finalKey);
        SemanticWorkPort.Response response;
        try { response=work.advance(request,requiredHash(manifest,"planFingerprint"),prefix,null,
                plan.status()==PlanStatus.COMPLETE?5_000L:remaining(plan),false); }
        catch (RuntimeException failure) { throw failed("SEMANTIC_CHECKPOINT_VALIDATION_FAILED"); }
        boolean marked=false;
        for (int round=0;round<=spec.callLimit();round++) {
            if (response==null) throw failed("SEMANTIC_WORK_RESPONSE_INVALID");
            if (response.result()!=null) {
                if (present(response.next()) || !response.result().available()) throw failed("SEMANTIC_WORK_RESPONSE_INVALID");
                List<Unit> finalUnits=checkpoints.findUnits(plan.planId());
                Unit synthesis=finalUnits.stream().filter(u -> u.logicalKey().equals(finalKey)).max(Comparator.comparingInt(Unit::executionNo))
                        .orElseThrow(() -> failed("SEMANTIC_FINAL_COVERAGE_INCOMPLETE"));
                if (synthesis.status()!=UnitStatus.VERIFIED) throw failed("SEMANTIC_FINAL_COVERAGE_INCOMPLETE");
                if (manifest.path("policy").has("monetaryObservationProtocol") && response.result().monetaryObservation() == null)
                    throw failed("SEMANTIC_WORK_RESPONSE_INVALID");
                SemanticMonetaryObservationVerifier.validateFinal(response.result().monetaryObservation(),
                        request.semanticMoneyMode(),finalUnits,synthesis,json,request.semanticHierarchyProtocol(),
                        manifest.path("policy").hasNonNull("businessAdapterProtocol")?manifest.path("policy").path("businessAdapterProtocol").asText():null);
                checkpoints.markComplete(access,synthesis.resultSha256());
                // Calls were committed per actual dispatch. Replaying checkpoints
                // must never fabricate new audit rows for this or a later Attempt.
                var value=response.result();
                return new ContractTextAnalysisResult(true,value.summaries(),value.hints(),value.model(),value.durationMs(),List.of(),value.monetaryObservation());
            }
            if (!present(response.next())) throw failed(safeFailure(response.failureCode()));
            if (plan.status()==PlanStatus.COMPLETE) throw failed("SEMANTIC_COMPLETION_UNPROVEN");
            JsonNode next=response.next();
            UnitSpec planned=specs.stream().filter(u -> u.logicalKey().equals(requiredHash(next,"logicalKey")))
                    .findFirst().orElseThrow(() -> failed("SEMANTIC_WORK_IDENTITY_INVALID"));
            Purpose purpose=Purpose.valueOf(next.path("purpose").asText());
            List<Unit> stored=checkpoints.findUnits(plan.planId());
            Unit prior=stored.stream().filter(u -> u.logicalKey().equals(planned.logicalKey()))
                    .max(Comparator.comparingInt(Unit::executionNo)).orElse(null);
            if (prior!=null && prior.status()==UnitStatus.FAILED && !prior.attemptId().equals(parent.attemptId()))
                purpose=Purpose.REPAIR; // explicit formal retry only; coordinator proves failed prior Attempt.
            String executionInput=purpose==Purpose.REPAIR ? requiredHash(next,"inputFingerprint") : planned.inputFingerprint();
            // Leave bounded short-transaction/HTTP handoff margin. Reserving the
            // exact previously-read deadline would already be stale at the CAS.
            long budget=Math.min(300_000L,remaining(plan)-1_000L);
            if (budget<=0) throw failed("SEMANTIC_TOTAL_BUDGET_EXHAUSTED");
            if (window != null && !window.equals(window(request))) throw failed("SEMANTIC_OBSERVE_WINDOW_INVALID");
            if (!marked) { markExternalRequested.run(); marked=true; }
            var metadata=new CallMetadata(UUID.randomUUID(),manifest.path("policy").path("provider").asText(),
                    manifest.path("policy").path("model").asText(),requiredHash(next,"operationFingerprint"));
            Unit sent;
            try { sent=checkpoints.reserveAndMarkSent(access,planned.logicalKey(),purpose,executionInput,budget,metadata); }
            catch (RuntimeException uncertainCommit) { throw unknown(); }
            SemanticWorkPort.Response received;
            try { received=work.advance(request,requiredHash(manifest,"planFingerprint"),prefix,
                    requiredHash(next,"operationFingerprint"),budget,true); }
            catch (RuntimeException failure) { unknown(access,sent); throw unknown(); }
            JsonNode checkpoint=received==null?null:received.checkpoint();
            try {
                validateCheckpoint(next,checkpoint,metadata);
                if (present(checkpoint.get("finalResult")) && !finalKey.equals(checkpoint.path("logicalKey").asText()))
                    throw failed("SEMANTIC_CHECKPOINT_INVALID");
                SemanticMonetaryObservationVerifier.validateCheckpoint(checkpoint,request.semanticMoneyMode(),json);
            } catch (RuntimeException invalid) { unknown(access,sent); throw unknown(); }
            UnitStatus status=UnitStatus.valueOf(checkpoint.path("status").asText());
            JsonNode call=checkpoint.path("call");
            if (status==UnitStatus.UNKNOWN) {
                checkpoints.markUnknown(access,sent.executionId(),safeReason(call.path("safeReason").asText()),
                        call.path("durationMs").asLong(),new CallUsage(nullable(call,"finishReason"),
                                integer(call,"promptTokens"),integer(call,"completionTokens"),integer(call,"totalTokens")));
                throw unknown();
            }
            String payload=write(checkpoint);
            Unit completed;
            try { completed=checkpoints.completeUnit(access,sent.executionId(),status,payload,digest(payload),
                    call.path("durationMs").asLong(),new CallUsage(nullable(call,"finishReason"),
                            integer(call,"promptTokens"),integer(call,"completionTokens"),integer(call,"totalTokens")),
                    nullable(call,"safeReason")); }
            catch (RuntimeException failure) { unknown(access,sent); throw unknown(); }
            if (status==UnitStatus.FAILED) throw failed("PROVIDER_5XX".equals(nullable(call,"safeReason"))
                    ? "SEMANTIC_PROVIDER_HTTP_ERROR" : safeFailure(received.failureCode()));
            prefix=new ArrayList<>(prefix); prefix.add(read(completed.resultJson()));
            plan=checkpoints.findPlan(parent.stepId()).orElse(plan);
            response=received;
            if(boundary!=SemanticCheckpointBoundary.NONE) {
                try { boundary.afterCommit(plan,checkpoints.findUnits(plan.planId())); }
                catch(RuntimeException interrupted) {
                    throw new ReviewStepOutcomeUnknownException("SEMANTIC_LOCAL_BOUNDARY_STOPPED",
                            new IllegalStateException("local maintenance boundary retained committed checkpoints"));
                }
            }
        }
        throw failed("SEMANTIC_TOTAL_BUDGET_EXHAUSTED");
    }

    private long remaining(Plan plan) {
        long wall=Duration.between(clock.instant(),plan.spec().deadlineAt()).toMillis();
        long consumed=plan.spec().timeLimitMs()-plan.usedMs()-plan.reservedMs();
        if (wall<=0 || consumed<=0) throw failed("SEMANTIC_TOTAL_BUDGET_EXHAUSTED");
        return Math.min(wall,consumed);
    }
    private SemanticObserveWindowPort.Window window(ContractTextAnalysisRequest request) {
        return window(request,false);
    }
    private SemanticObserveWindowPort.Window window(ContractTextAnalysisRequest request, boolean completedReplay) {
        if (!"OBSERVE".equals(request.semanticMoneyMode())) return null;
        try {
            if (observeWindow == null) throw new IllegalStateException();
            return completedReplay ? observeWindow.readIdentity(clock.instant()) : observeWindow.read(clock.instant());
        } catch (RuntimeException invalid) { throw failed("SEMANTIC_OBSERVE_WINDOW_INVALID"); }
    }
    private List<JsonNode> prefix(List<Unit> units, String finalKey) {
        if (units.stream().anyMatch(u -> u.status()==UnitStatus.SENT || u.status()==UnitStatus.UNKNOWN)) throw unknown();
        List<JsonNode> result=new ArrayList<>();
        for (Unit unit:units.stream().sorted(Comparator.comparingInt(Unit::ordinal).thenComparingInt(Unit::executionNo)).toList()) {
            // A formal later Attempt can have a verified successor execution
            // under the same logical key. Retain the failed audit row but do
            // not let it hide that successor during deterministic validation.
            if (unit.status()==UnitStatus.FAILED) continue;
            if (unit.status()==UnitStatus.VERIFIED || unit.status()==UnitStatus.REPAIR_REQUIRED) {
                if (unit.resultJson()==null || !digest(unit.resultJson()).equals(unit.resultSha256()))
                    throw failed("SEMANTIC_CHECKPOINT_INVALID");
                JsonNode packet = read(unit.resultJson());
                if (present(packet.get("finalResult")) && !unit.logicalKey().equals(finalKey))
                    throw failed("SEMANTIC_CHECKPOINT_INVALID");
                result.add(packet);
            }
        }
        return List.copyOf(result);
    }
    private void validateManifest(JsonNode manifest) {
        if (manifest==null || !manifest.isObject() || manifest.path("version").asInt()!=2
                || !manifest.path("units").isArray() || manifest.path("units").isEmpty()
                || manifest.path("units").size()>62 || manifest.path("timeLimitMs").asLong()<1
                || manifest.path("timeLimitMs").asLong()>3_600_000L
                || manifest.path("callLimit").asInt()!=manifest.path("units").size()+manifest.path("repairLimit").asInt()
                || manifest.path("repairLimit").asInt()!=2
                || !Set.of("OPENAI_COMPATIBLE","OLLAMA_NATIVE").contains(manifest.path("policy").path("provider").asText()))
            throw failed("SEMANTIC_PLAN_INVALID");
        if (!ContractReviewExecutionPolicyResolver.DURABLE_WORK_POLICY_VERSION
                .equals(manifest.path("policy").path("version").asText()))
            throw failed("SEMANTIC_POLICY_INCOMPATIBLE");
        requiredHash(manifest,"planFingerprint");requiredHash(manifest,"inputFingerprint");requiredHash(manifest,"policyFingerprint");
    }
    private void validateCheckpoint(JsonNode expected,JsonNode value,CallMetadata metadata) {
        if (!present(value) || !value.isObject() || !Set.of("VERIFIED","REPAIR_REQUIRED","FAILED","UNKNOWN")
                .contains(value.path("status").asText()) || !value.path("call").isObject()
                || !expected.path("logicalKey").equals(value.path("logicalKey"))
                || !expected.path("purpose").equals(value.path("purpose"))
                || !expected.path("inputFingerprint").equals(value.path("inputFingerprint"))
                || !expected.path("operationFingerprint").equals(value.path("operationFingerprint"))
                || value.path("ordinal").asInt()!=expected.path("ordinal").asInt()
                || !metadata.provider().equals(value.path("call").path("provider").asText())
                || !metadata.model().equals(value.path("call").path("model").asText())
                || !value.path("call").path("durationMs").isIntegralNumber()
                || value.path("call").path("durationMs").asLong()<0
                || write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262_144)
            throw failed("SEMANTIC_CHECKPOINT_INVALID");
        requiredHash(value,"checkpointHash");
        boolean success="VERIFIED".equals(value.path("status").asText());
        if (success != "SUCCEEDED".equals(value.path("call").path("outcome").asText()))
            throw failed("SEMANTIC_CHECKPOINT_INVALID");
    }
    private void unknown(Access access,Unit sent) {
        try { checkpoints.markUnknown(access,sent.executionId(),"TRANSPORT_OUTCOME_UNKNOWN"); }
        catch (RuntimeException closedOrFenced) { /* Recovery rediscovery owns ambiguous commits; never resend. */ }
    }
    private static boolean present(JsonNode node) { return node!=null && !node.isNull() && !node.isMissingNode(); }
    private static String requiredHash(JsonNode node,String name) { String value=node.path(name).asText();hash(value);return value; }
    private static String nullable(JsonNode node,String name) { return present(node.get(name))?node.path(name).asText():null; }
    private static Integer integer(JsonNode node,String name) { return present(node.get(name))?node.path(name).asInt():null; }
    private static String safeReason(String value) { return value!=null && value.matches("[A-Z0-9_]{1,64}")?value:"TRANSPORT_OUTCOME_UNKNOWN"; }
    private static String safeFailure(String value) { return switch(value==null?"":value) {
        case "MODEL_TOTAL_BUDGET_EXHAUSTED" -> "SEMANTIC_TOTAL_BUDGET_EXHAUSTED";
        case "SEMANTIC_SYNTHESIS_INPUT_BOUND", "SEMANTIC_CHECKPOINT_OUTPUT_BOUND" -> value;
        default -> "SEMANTIC_MODEL_INVALID_RESULT"; }; }
    private static ReviewStepProvenFailureException failed(String reason) {
        return new ReviewStepProvenFailureException(reason,new IllegalStateException("semantic work stopped safely"));
    }
    private static ReviewStepOutcomeUnknownException unknown() {
        return new ReviewStepOutcomeUnknownException("SEMANTIC_OUTCOME_UNKNOWN",new IllegalStateException("semantic dispatch outcome is unproven"));
    }
    private String write(JsonNode node) { try { return json.writeValueAsString(node); }
        catch (java.io.IOException invalid) { throw failed("SEMANTIC_CHECKPOINT_INVALID"); } }
    private JsonNode read(String value) { try { return json.readTree(value); }
        catch (java.io.IOException invalid) { throw failed("SEMANTIC_CHECKPOINT_INVALID"); } }
}
