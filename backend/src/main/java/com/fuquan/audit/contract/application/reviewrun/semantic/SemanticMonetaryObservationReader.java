package com.fuquan.audit.contract.application.reviewrun.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateResult;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.util.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;

/** Reopens the exact run's two-table ledger. It never asks Python to plan or replay. */
@Component
public final class SemanticMonetaryObservationReader {
    private final SemanticCheckpointRepository repository;
    private final ObjectMapper json;
    public SemanticMonetaryObservationReader(@Nullable SemanticCheckpointRepository repository, ObjectMapper json) {
        this.repository = repository; this.json = json;
    }

    public ContractMonetaryObservation read(ContractReviewRunCreateResult manifest,
            List<ReviewStepOutputReadService.StepOutput> outputs) {
        var run = manifest.run();
        if (!ContractReviewExecutionPolicyResolver.isDurableModelPolicy(run.modelPolicyVersion())) return null;
        String mode = ContractReviewExecutionPolicyResolver.moneyModeForPolicy(run.modelPolicyVersion());
        var step = outputs.stream().filter(value -> value.step().stepType() == ContractReviewStepType.SEMANTIC_AI)
                .findFirst().orElseThrow(SemanticMonetaryObservationReader::invalid);
        if (repository == null) {
            if ("OBSERVE".equals(mode)) throw invalid();
            return null;
        }
        var plan = repository.findPlanByStep(step.step().stepId()).orElse(null);
        if (plan == null) {
            if (step.step().status() == ContractReviewStepStatus.COMPLETED && "OBSERVE".equals(mode)) throw invalid();
            return "OBSERVE".equals(mode) ? new ContractMonetaryObservation(ContractMonetaryObservation.PROTOCOL, mode, List.of(), List.of()) : null;
        }
        if (!plan.runId().equals(run.runId()) || !plan.stepId().equals(step.step().stepId())
                || !run.modelPolicyVersion().equals(step.step().stepPolicyVersion())) throw invalid();
        JsonNode envelope = tree(plan.spec().planJson());
        JsonNode python = envelope.path("pythonManifest");
        SemanticMonetaryObservationVerifier.validatePolicy(python.path("policy"), mode);
        if (!run.inputSnapshotIdentity().equals(envelope.path("frozenInputIdentity").asText())) throw invalid();
        // A legacy strict ledger has no observation protocol. Its historical output remains readable.
        if (!python.path("policy").has("monetaryObservationProtocol") && "STRICT".equals(mode)) return null;
        final String finalKey;
        try { finalKey = SemanticWorkManifest.validateStoredSpec(plan.spec(), python,
                ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy(run.modelPolicyVersion())); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        if (!plan.spec().policyFingerprint().equals(python.path("policyFingerprint").asText())
                || !plan.spec().policyFingerprint().equals(SemanticMonetaryObservationVerifier.canonicalHash(python.path("policy")))) throw invalid();
        var manifestIdentity = ((com.fasterxml.jackson.databind.node.ObjectNode)python).deepCopy();
        manifestIdentity.remove("planFingerprint");
        if (!python.path("planFingerprint").asText().equals(SemanticMonetaryObservationVerifier.canonicalHash(manifestIdentity))
                || !plan.spec().inputFingerprint().equals(digest(run.inputSnapshotIdentity()+"\n"+python.path("inputFingerprint").asText()))) throw invalid();
        Map<String,JsonNode> required = new HashMap<>();
        for (var planned : python.path("units")) {
            if (required.putIfAbsent(planned.path("logicalKey").asText(),planned) != null) throw invalid();
        }
        if (required.isEmpty()) throw invalid();
        Set<UUID> attempts = new HashSet<>(); step.attempts().forEach(value -> attempts.add(value.attemptId()));
        if (!attempts.contains(plan.originalAttemptId()) || !attempts.contains(plan.currentAttemptId())) throw invalid();
        var units = repository.findUnits(plan.planId());
        Set<UUID> executions = new HashSet<>();
        for (var unit : units) {
            if (!unit.planId().equals(plan.planId()) || !attempts.contains(unit.attemptId()) || !executions.add(unit.executionId())
                    || !required.containsKey(unit.logicalKey()) || required.get(unit.logicalKey()).path("ordinal").asInt() != unit.ordinal()) throw invalid();
            if (unit.resultJson() == null) continue;
            if (!digest(unit.resultJson()).equals(unit.resultSha256())) throw invalid();
            var packet = tree(unit.resultJson());
            if (!unit.logicalKey().equals(packet.path("logicalKey").asText())
                    || unit.ordinal() != packet.path("ordinal").asInt()
                    || !unit.purpose().name().equals(packet.path("purpose").asText())
                    || !unit.status().name().equals(packet.path("status").asText())
                    || unit.call() == null || !unit.call().requestFingerprint().equals(packet.path("operationFingerprint").asText())) throw invalid();
            if (packet.hasNonNull("finalResult") && !unit.logicalKey().equals(finalKey)) throw invalid();
            SemanticMonetaryObservationVerifier.validateCheckpoint(packet, mode, json);
        }
        if (step.step().status() != ContractReviewStepStatus.COMPLETED) return new ContractMonetaryObservation(
                ContractMonetaryObservation.PROTOCOL, mode, List.of(), SemanticMonetaryObservationVerifier.workWarnings(units, mode, json));
        if (plan.status() != PlanStatus.COMPLETE) throw invalid();
        for (var planned : required.keySet()) {
            var latest = units.stream().filter(value -> value.logicalKey().equals(planned))
                    .max(Comparator.comparingInt(Unit::executionNo)).orElseThrow(SemanticMonetaryObservationReader::invalid);
            if (latest.status() != UnitStatus.VERIFIED) throw invalid();
        }
        var synthesis = units.stream().filter(value -> finalKey.equals(value.logicalKey()))
                .max(Comparator.comparingInt(Unit::executionNo)).orElseThrow(SemanticMonetaryObservationReader::invalid);
        if (synthesis.status() != UnitStatus.VERIFIED || !synthesis.resultSha256().equals(plan.finalResultSha256())) throw invalid();
        var finalResult = tree(synthesis.resultJson()).path("finalResult");
        var observation = SemanticMonetaryObservationVerifier.decode(finalResult.get("monetaryObservation"), json);
        if (observation == null) throw invalid();
        SemanticMonetaryObservationVerifier.validateFinal(observation, mode, units, synthesis, json,
                ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy(run.modelPolicyVersion()),
                python.path("policy").hasNonNull("businessAdapterProtocol")?python.path("policy").path("businessAdapterProtocol").asText():null);
        var hints = finalResult.path("hints");
        if (!hints.isArray() || hints.size() != step.findings().size()) throw invalid();
        for (var finding : step.findings()) {
            if (finding.ordinal() > hints.size()) throw invalid();
            var hint = hints.get(finding.ordinal() - 1);
            if (!finding.summary().equals(hint.path("title").asText())
                    || !finding.recommendation().equals(hint.path("description").asText())
                    || !finding.category().equals(hint.path("category").asText())
                    || !finding.attentionCode().equals(hint.path("attention").asText())) throw invalid();
        }
        return observation;
    }
    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException | RuntimeException invalid) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("durable semantic monetary observation cannot be verified"); }
}
