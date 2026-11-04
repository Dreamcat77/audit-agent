package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointCoordinator;
import com.fuquan.audit.contract.application.reviewrun.semantic.DurableSemanticExecution;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticWorkPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.digest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReviewWorkerTransactionCoordinatorTest {
    static final Instant NOW = Instant.parse("2026-09-16T01:00:00Z");
    private static final String CHECKPOINT_POLICY = "contract-semantic-work-v3-redaction-v1-grounding-v1";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {CHECKPOINT_POLICY,
            "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1",
            "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1",
            "contract-semantic-java-schema-v1-redaction-v1-grounding-v1"})
    void semanticClaimAcceptsTheExplicitPolicyFrozenOnBothRunAndStep(String policy) {
        var f = new Fixture(ContractReviewStepType.SEMANTIC_AI);
        frozenSemanticPolicy(f, policy, policy);
        ReviewStepStartTransactionCoordinator.StartedExecution[] claimed = new ReviewStepStartTransactionCoordinator.StartedExecution[1];

        assertThatCode(() -> claimed[0] = f.claim("worker-a")).doesNotThrowAnyException();

        assertThat(claimed[0].run().modelPolicyVersion()).isEqualTo(policy);
        assertThat(claimed[0].step().stepPolicyVersion()).isEqualTo(policy);
        assertThat(claimed[0].attempt().externalCallState()).isEqualTo(ReviewStepExternalCallState.NOT_STARTED);
    }

    @Test void historicalV2SemanticClaimIsRejectedBeforeTakingAnyLeaseOrIdempotencyClaim() {
        var f = new Fixture(ContractReviewStepType.SEMANTIC_AI);
        frozenSemanticPolicy(f, "contract-semantic-work-v2-redaction-v1-grounding-v1",
                "contract-semantic-work-v2-redaction-v1-grounding-v1");
        var previous = f.attempt.get();

        assertThatThrownBy(() -> f.claim("worker-v3")).isInstanceOf(BusinessException.class);

        assertThat(f.attempt.get()).isEqualTo(previous);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                "reviewworker:" + previous.operationIdentity())).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"contract-semantic-work-v2", "contract-semantic-work-v4"})
    void currentRunCannotRecoverASelfConsistentCheckpointFromAnotherPythonPolicy(String pythonPolicy) {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.VERIFIED, pythonPolicy);
        f.semanticCheckpoints = ledger;
        f.coordinator = f.coordinator();
        var previousPlan = ledger.plan;
        var previousUnits = List.copyOf(ledger.units.values());

        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-v3")).isEmpty();

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
        assertThat(ledger.plan).isEqualTo(previousPlan);
        assertThat(ledger.units.values()).containsExactlyElementsOf(previousUnits);
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void semanticClaimRejectsCrossedRunAndStepPoliciesBeforeTakingAnyLease() {
        var f = new Fixture(ContractReviewStepType.SEMANTIC_AI);
        frozenSemanticPolicy(f, CHECKPOINT_POLICY, "contract-semantic-java-schema-v1-redaction-v1-grounding-v1");

        assertThatThrownBy(() -> f.claim("worker-a")).isInstanceOf(BusinessException.class);

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.PENDING);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                "reviewworker:" + f.attempt.get().operationIdentity())).isEmpty();
    }

    private static void frozenSemanticPolicy(Fixture f, String runPolicy, String stepPolicy) {
        var r = f.run.get();
        f.run.set(new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), r.contractVersion(),
                r.sourceCandidateId(), r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(),
                r.contractSnapshotSha256(), r.reviewProfileVersion(), r.ruleVersion(), runPolicy, r.inputSnapshotIdentity(),
                r.requestedBy(), r.status(), r.requestedAt(), r.startedAt(), r.completedAt(), r.failedAt(), r.unknownAt(),
                r.safeReasonCode(), r.versionNo()));
        var s = f.step.get();
        f.step.set(new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), s.required(), s.status(), stepPolicy,
                s.currentAttemptNo(), s.outputResourceType(), s.outputResourceId(), s.safeReasonCode(),
                s.startedAt(), s.completedAt(), s.versionNo()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = UnitStatus.class,
            names = {"PLANNED", "VERIFIED", "REPAIR_REQUIRED", "SENT", "UNKNOWN"})
    void expiredCheckpointAttemptRetainsOutboundIdentityAndOnlyHandsOffFencedRecovery(UnitStatus status) {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, status);
        f.semanticCheckpoints = ledger;
        f.coordinator = f.coordinator();
        var previous = f.attempt.get();
        var oldExecution = checkpointExecution(f);

        var recovery = f.coordinator.recover(f.currentCandidate(), "worker-b");
        assertThat(recovery).isPresent();
        var recovered = recovery.orElseThrow();

        assertThat(recovered.attempt().attemptId()).isEqualTo(previous.attemptId());
        assertThat(recovered.attempt().operationIdentity()).isEqualTo(previous.operationIdentity());
        assertThat(recovered.attempt().externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        assertThat(recovered.attempt().externalRequestFingerprint()).isEqualTo(previous.externalRequestFingerprint());
        assertThat(recovered.attempt().leaseOwner()).isEqualTo("worker-b");
        assertThat(recovered.attempt().attemptNo()).isEqualTo(1);
        assertThat(f.run.get().status()).isEqualTo(ContractReviewRunStatus.RUNNING);
        assertThat(ledger.units.values()).extracting(Unit::status).contains(status);
        assertThatThrownBy(() -> f.coordinator.complete(oldExecution, f.emptyResult(oldExecution)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> f.coordinator.markRequested(recovered, "d".repeat(64)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-c")).isEmpty();
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void reclaimedSentCheckpointBecomesUnknownWithAuditBeforeParentClosesWithoutDispatch() throws Exception {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.SENT);
        f.semanticCheckpoints = ledger; f.coordinator = f.coordinator();
        var recovery = f.coordinator.recover(f.currentCandidate(), "worker-b");
        assertThat(recovery).isPresent();
        var recovered = recovery.orElseThrow();
        Clock clock = Clock.fixed(f.now.get(), ZoneOffset.UTC);
        var checkpoints = new SemanticCheckpointCoordinator(ledger, f.runs, f.steps, f.attempts, f.modelCalls, clock);
        var originalUnit = ledger.units.values().iterator().next();
        assertThatThrownBy(() -> checkpoints.completeUnit(new Access(new Parent(recovered.run().runId(),
                recovered.step().stepId(), recovered.attempt().attemptId(), "worker-a"), ledger.plan.planId(), 1),
                originalUnit.executionId(), UnitStatus.VERIFIED, "{}", digest("{}"), 1,
                new CallUsage("STOP", 1, 1, 2), null)).isInstanceOf(IllegalStateException.class);
        assertThat(f.modelCalls.findByReviewStepAttempt(recovered.attempt().attemptId())).isEmpty();
        var work = mock(SemanticWorkPort.class);
        var marker = mock(Runnable.class);
        when(work.plan(any())).thenReturn(new ObjectMapper().readTree(ledger.plan.spec().planJson()).path("pythonManifest"));
        var executor = new DurableSemanticExecution(checkpoints, work, new ObjectMapper(), clock);
        var request = new ContractTextAnalysisRequest(recovered.run().projectId(), List.of(
                new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "synthetic frozen source")));

        assertThatThrownBy(() -> executor.execute(request, new Parent(recovered.run().runId(), recovered.step().stepId(),
                recovered.attempt().attemptId(), "worker-b"), recovered.run().inputSnapshotIdentity(), marker))
                .isInstanceOf(ReviewStepOutcomeUnknownException.class);

        assertThat(ledger.units.values()).extracting(Unit::status).containsExactly(UnitStatus.UNKNOWN, UnitStatus.PLANNED);
        assertThat(f.modelCalls.findByReviewStepAttempt(recovered.attempt().attemptId())).hasSize(1)
                .allSatisfy(call -> assertThat(call.safeReason()).isEqualTo("TRANSPORT_OUTCOME_UNKNOWN"));
        f.coordinator.close(recovered, ContractReviewStepStatus.UNKNOWN, "SEMANTIC_OUTCOME_UNKNOWN");
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        verify(work, never()).advance(any(), anyString(), anyList(), any(), anyLong(), anyBoolean());
        verifyNoInteractions(marker);
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void unknownCheckpointDominatesAnotherKnownFailedUnitDuringRecovery() {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.FAILED);
        Unit second = ledger.units.values().stream().filter(unit -> unit.ordinal() == 2).findFirst().orElseThrow();
        ledger.units.put(second.executionId(), new Unit(second.executionId(), second.planId(), second.logicalKey(),
                1, second.ordinal(), second.purpose(), second.attemptId(), second.inputFingerprint(), second.dependencies(),
                UnitStatus.UNKNOWN, second.owner(), second.fence(), second.leaseUntil(), 100, 0, null, null,
                "TRANSPORT_OUTCOME_UNKNOWN", new CallMetadata(UUID.randomUUID(), "OPENAI_COMPATIBLE", "unit-model", "f".repeat(64)),
                2, 1, NOW, NOW));
        f.semanticCheckpoints = ledger; f.coordinator = f.coordinator();

        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isPresent();

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        assertThat(ledger.units.values()).extracting(Unit::status).contains(UnitStatus.UNKNOWN);
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void unreadableCheckpointStorageIsNeverTreatedAsProvenAbsenceOrFailure() {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        f.semanticCheckpoints = mock(SemanticCheckpointRepository.class);
        when(f.semanticCheckpoints.lockPlanByStep(any())).thenThrow(new IllegalStateException("synthetic storage unknown"));
        f.coordinator = f.coordinator();
        var previous = f.attempt.get();

        assertThatThrownBy(() -> f.coordinator.recover(f.currentCandidate(), "worker-b"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(f.attempt.get()).isEqualTo(previous);
        assertThat(f.events).isEmpty();
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                "reviewworker:" + previous.operationIdentity()).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void recoveryVerifiesPythonCanonicalHashesIncludingUnicodeAndFloatingBudgetMetadata() throws Exception {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.VERIFIED);
        var document = new ObjectMapper().readTree(ledger.plan.spec().planJson());
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("pythonManifest");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("policy"))
                .put("label", "单元😀").put("budgetSeconds", 900.0);
        // Computed independently with Python json.dumps(sort_keys=True, ensure_ascii=False, separators=(',', ':')).
        String policyHash = "f58a089f0ddc72384095428d86f08246348f80eee6a3ffe464e9a102e0da3b5b";
        manifest.put("policyFingerprint", policyHash);
        manifest.put("planFingerprint", "70419c211e648fb9d869b2880023026580347b1c369514e8818594f4d74ef2b7");
        var p = ledger.plan; var s = p.spec();
        ledger.plan = new Plan(p.planId(), p.runId(), p.stepId(), p.originalAttemptId(), p.currentAttemptId(),
                new PlanSpec(s.inputFingerprint(), policyHash, document.toString(), s.deadlineAt(), s.timeLimitMs(),
                        s.callLimit(), s.repairLimit()), p.owner(), p.fence(), p.leaseUntil(), p.status(), p.usedCalls(),
                p.reservedCalls(), p.usedRepairs(), p.reservedRepairs(), p.usedMs(), p.reservedMs(), null,
                p.versionNo(), p.createdAt(), p.updatedAt());
        f.semanticCheckpoints = ledger; f.coordinator = f.coordinator();

        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isPresent();

        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        assertThat(ledger.plan.spec().planJson()).contains("单元");
        verifyNoInteractions(f.completes, f.outputs);
    }

    @Test void provenFailedCheckpointClosesOnlyCurrentParentAttemptAndCannotAutomaticallyRetry() {
        var f = requestedCheckpointFixture(CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.FAILED);
        f.semanticCheckpoints = ledger; f.coordinator = f.coordinator();
        var before = f.attempt.get();

        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isEmpty();

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.FAILED);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.COMPLETED);
        assertThat(f.attempt.get().safeReasonCode()).isEqualTo("WORKER_SEMANTIC_CHECKPOINT_FAILED");
        assertThat(f.attempt.get().attemptId()).isEqualTo(before.attemptId());
        assertThat(f.attempt.get().attemptNo()).isEqualTo(1);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                "reviewworker:" + before.operationIdentity()).orElseThrow().status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(ledger.units.values()).extracting(Unit::status).contains(UnitStatus.FAILED);
        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-c")).isEmpty();
        verifyNoInteractions(f.completes, f.outputs);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"legacy", "missing", "wrong-run", "wrong-step",
            "wrong-attempt", "wrong-input", "wrong-policy-hash", "wrong-plan-hash", "unknown-run"})
    void incompatibleOrLegacyCheckpointNeverReentersAnExecutor(String defect) throws Exception {
        var f = requestedCheckpointFixture(defect.equals("legacy")
                ? "contract-semantic-java-schema-v1-redaction-v1-grounding-v1" : CHECKPOINT_POLICY);
        var ledger = new CheckpointLedger(f, UnitStatus.VERIFIED);
        f.semanticCheckpoints = ledger;
        if (defect.equals("missing")) ledger.plan = null;
        else if (defect.equals("wrong-run") || defect.equals("wrong-step") || defect.equals("wrong-attempt")) {
            var p = ledger.plan;
            ledger.plan = new Plan(p.planId(), defect.equals("wrong-run") ? UUID.randomUUID() : p.runId(),
                    defect.equals("wrong-step") ? UUID.randomUUID() : p.stepId(), p.originalAttemptId(),
                    defect.equals("wrong-attempt") ? UUID.randomUUID() : p.currentAttemptId(), p.spec(), p.owner(),
                    p.fence(), p.leaseUntil(), p.status(), p.usedCalls(), p.reservedCalls(), p.usedRepairs(),
                    p.reservedRepairs(), p.usedMs(), p.reservedMs(), null, p.versionNo(), p.createdAt(), p.updatedAt());
        } else if (defect.startsWith("wrong-")) {
            var document = new ObjectMapper().readTree(ledger.plan.spec().planJson());
            if (defect.equals("wrong-input")) ((com.fasterxml.jackson.databind.node.ObjectNode) document)
                    .put("frozenInputIdentity", "f".repeat(64));
            else ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("pythonManifest"))
                    .put(defect.equals("wrong-policy-hash") ? "policyFingerprint" : "planFingerprint", "0".repeat(64));
            var old = ledger.plan.spec();
            var replacement = new PlanSpec(old.inputFingerprint(), old.policyFingerprint(), document.toString(),
                    old.deadlineAt(), old.timeLimitMs(), old.callLimit(), old.repairLimit());
            var p = ledger.plan;
            ledger.plan = new Plan(p.planId(), p.runId(), p.stepId(), p.originalAttemptId(), p.currentAttemptId(),
                    replacement, p.owner(), p.fence(), p.leaseUntil(), p.status(), p.usedCalls(), p.reservedCalls(),
                    p.usedRepairs(), p.reservedRepairs(), p.usedMs(), p.reservedMs(), null, p.versionNo(), p.createdAt(), p.updatedAt());
        }
        if (defect.equals("unknown-run")) {
            var r = f.run.get(); f.run.set(new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), 0,
                    null, null, r.runSchemaVersion(), r.contractSnapshotCanonicalJson(), r.contractSnapshotSha256(),
                    r.reviewProfileVersion(), r.ruleVersion(), r.modelPolicyVersion(), r.inputSnapshotIdentity(),
                    r.requestedBy(), ContractReviewRunStatus.UNKNOWN, NOW, NOW, null, null, NOW, "UNKNOWN", r.versionNo()));
        }
        f.coordinator = f.coordinator();

        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isEmpty();

        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.attempt.get().externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
        verifyNoInteractions(f.completes, f.outputs);
    }

    private static Fixture requestedCheckpointFixture(String policy) {
        var f = new Fixture(ContractReviewStepType.SEMANTIC_AI);
        var r = f.run.get();
        f.run.set(new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), 0, null, null, r.runSchemaVersion(),
                r.contractSnapshotCanonicalJson(), r.contractSnapshotSha256(), r.reviewProfileVersion(), r.ruleVersion(),
                policy, r.inputSnapshotIdentity(), r.requestedBy(), ContractReviewRunStatus.RUNNING, NOW, NOW,
                null, null, null, null, 1));
        var s = f.step.get();
        f.step.set(new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), true, ContractReviewStepStatus.RUNNING,
                policy, 1, null, null, null, NOW, null, 1));
        var a = f.attempt.get();
        f.attempt.set(new ContractReviewStepAttempt(a.attemptId(), a.runId(), a.stepId(), 1, a.operationIdentity(),
                ContractReviewStepStatus.RUNNING, "worker-a", NOW.plusSeconds(60), NOW,
                ReviewStepExternalCallState.REQUESTED, "b".repeat(64), null, null, null, NOW,
                null, null, null, null, 2));
        var actor = new com.fuquan.audit.security.domain.CurrentUser("initiator-1", "auditor", "Audit User", Set.of());
        f.idempotency.claim(new IdempotencyRequest(ReviewStepStartTransactionCoordinator.SCOPE,
                "reviewworker:" + a.operationIdentity(), actor.userId(), "CONTRACT_REVIEW_STEP", a.stepId().toString(),
                ReviewStepStartTransactionCoordinator.fingerprint(actor, f.run.get(), f.step.get(), f.attempt.get()),
                "CONTRACT_REVIEW_STEP_ATTEMPT", a.attemptId().toString(), "request", "trace", NOW));
        f.now.set(NOW.plusSeconds(60));
        return f;
    }

    private static ReviewStepStartTransactionCoordinator.StartedExecution checkpointExecution(Fixture f) {
        return new ReviewStepStartTransactionCoordinator.StartedExecution(f.run.get(), f.step.get(), f.attempt.get(),
                f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                        "reviewworker:" + f.attempt.get().operationIdentity()).orElseThrow(),
                new com.fuquan.audit.security.domain.CurrentUser("initiator-1", "auditor", "Audit User", Set.of()),
                new ReviewStepStartTransactionCoordinator.StartCommand(f.run.get().projectId(), f.run.get().contractId(),
                        f.run.get().runId(), f.step.get().stepId(), "reviewworker:" + f.attempt.get().operationIdentity(),
                        new com.fuquan.audit.contract.application.project.ProjectRequestMetadata("request", "trace", null)), List.of());
    }

    private static final class CheckpointLedger implements SemanticCheckpointRepository {
        Plan plan;
        final Map<UUID, Unit> units = new LinkedHashMap<>();
        final List<UnitSpec> specs = List.of(new UnitSpec("1".repeat(64), 1, Purpose.ANALYZE, "e".repeat(64), List.of()),
                new UnitSpec("2".repeat(64), 2, Purpose.SYNTHESIZE, "e".repeat(64), List.of("1".repeat(64))));
        CheckpointLedger(Fixture f, UnitStatus status) {
            this(f, status, "contract-semantic-work-v3");
        }
        CheckpointLedger(Fixture f, UnitStatus status, String pythonPolicy) {
            try {
                var json = new ObjectMapper();
                var policy = new TreeMap<String, Object>(Map.of("model", "unit-model", "provider", "OPENAI_COMPATIBLE",
                        "version", pythonPolicy));
                String policyHash = digest(json.writeValueAsString(policy));
                var manifest = new TreeMap<String, Object>();
                manifest.put("inputFingerprint", "e".repeat(64)); manifest.put("policy", policy);
                manifest.put("policyFingerprint", policyHash);
                manifest.put("version", 2); manifest.put("timeLimitMs", 900000);
                manifest.put("callLimit", 4); manifest.put("repairLimit", 2);
                manifest.put("units", specs.stream().map(unit -> new TreeMap<String, Object>(Map.of(
                        "logicalKey", unit.logicalKey(), "ordinal", unit.ordinal(), "purpose", unit.purpose().name(),
                        "inputFingerprint", unit.inputFingerprint(), "dependencies", unit.dependencies()))).toList());
                manifest.put("planFingerprint", digest(json.writeValueAsString(manifest)));
                var envelope = new TreeMap<String, Object>();
                envelope.put("frozenInputIdentity", f.run.get().inputSnapshotIdentity()); envelope.put("pythonManifest", manifest);
                var spec = new PlanSpec(digest(f.run.get().inputSnapshotIdentity() + "\n" + "e".repeat(64)), policyHash,
                        json.writeValueAsString(envelope), NOW.plusSeconds(900), 900000, 4, 2);
                boolean sent = status == UnitStatus.SENT || status == UnitStatus.UNKNOWN;
                plan = new Plan(UUID.randomUUID(), f.run.get().runId(), f.step.get().stepId(), f.attempt.get().attemptId(),
                        f.attempt.get().attemptId(), spec, "worker-a", 1, NOW.plusSeconds(60), PlanStatus.ACTIVE,
                        sent || status == UnitStatus.PLANNED ? 0 : 1, sent ? 1 : 0, 0, 0, 0, sent ? 100 : 0,
                        null, 0, NOW, NOW);
                for (UnitSpec unit : specs) {
                    UnitStatus state = unit.ordinal() == 1 ? status : UnitStatus.PLANNED;
                    boolean call = state != UnitStatus.PLANNED;
                    String result = state == UnitStatus.VERIFIED || state == UnitStatus.REPAIR_REQUIRED ? "{}" : null;
                    Unit value = new Unit(UUID.randomUUID(), plan.planId(), unit.logicalKey(), 1, unit.ordinal(), unit.purpose(),
                            f.attempt.get().attemptId(), unit.inputFingerprint(), unit.dependencies(), state, "worker-a", 1,
                            NOW.plusSeconds(60), sent && unit.ordinal() == 1 ? 100 : 0, 0, result,
                            result == null ? null : digest(result), state == UnitStatus.FAILED ? "PROVIDER_5XX"
                                    : state == UnitStatus.UNKNOWN ? "TRANSPORT_OUTCOME_UNKNOWN" : null,
                            call ? new CallMetadata(UUID.randomUUID(), "OPENAI_COMPATIBLE", "unit-model", "f".repeat(64)) : null,
                            call ? 1 : 0, 0, NOW, NOW);
                    units.put(value.executionId(), value);
                }
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        }
        public Optional<Plan> findPlanByStep(UUID id) { return Optional.ofNullable(plan); }
        public Optional<Plan> lockPlanByStep(UUID id) { return findPlanByStep(id); }
        public void lockParentAttempt(UUID id) { }
        public List<Unit> findUnits(UUID id) { return List.copyOf(units.values()); }
        public void insertPlan(Plan value) { throw new AssertionError("recovery cannot insert plans"); }
        public void insertUnit(Unit value) { throw new AssertionError("recovery cannot dispatch or insert units"); }
        public void compareAndSetPlan(Plan expected, Plan next) {
            if (!Objects.equals(plan, expected)) throw new IllegalStateException("lost plan fence"); plan = next;
        }
        public void compareAndSetUnit(Unit expected, Unit next) {
            if (!Objects.equals(units.get(expected.executionId()), expected)) throw new IllegalStateException("lost unit fence");
            units.put(next.executionId(), next);
        }
    }

    @Test void twoWorkersRaceOnlyOneClaimWinsAndBusinessIdentityHasNoFabricatedRoles() throws Exception {
        var f = new Fixture(ContractReviewStepType.BREACH);
        var barrier = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { barrier.await(); return f.tryClaim("worker-a"); });
            var b = pool.submit(() -> { barrier.await(); return f.tryClaim("worker-b"); });
            barrier.countDown();
            var outcomes = Arrays.asList(a.get(), b.get());
            assertThat(outcomes.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var claim = outcomes.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(claim.actor().userId()).isEqualTo("initiator-1");
            assertThat(claim.actor().roles()).isEmpty();
            assertThat(claim.attempt().leaseOwner()).startsWith("worker-");
            assertThat(f.events).singleElement().satisfies(event -> {
                assertThat(event.userId()).isEqualTo("initiator-1");
                assertThat(event.taskId()).isNull();
                assertThat(event.detail()).contains(claim.attempt().leaseOwner());
            });
        } finally { pool.shutdownNow(); }
    }

    @Test void heartbeatFencesEveryLateTerminalWriterBeforeOutputPublication() {
        for (var action : List.of("complete", "fail", "unknown")) {
            var f = new Fixture(ContractReviewStepType.BREACH);
            var old = f.claim("worker-a");
            f.now.set(NOW.plusSeconds(10));
            var renewed = f.coordinator.heartbeat(old);
            assertThat(renewed.attempt().versionNo()).isEqualTo(2);
            assertThatThrownBy(() -> {
                if (action.equals("complete")) f.coordinator.complete(old, f.emptyResult(old));
                else f.coordinator.close(old, action.equals("fail") ? ContractReviewStepStatus.FAILED
                        : ContractReviewStepStatus.UNKNOWN, "SAFE_FAILURE");
            }).isInstanceOf(BusinessException.class);
            assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            verifyNoInteractions(f.completes);
        }
    }

    @Test void terminalWriteRequiresCurrentAttemptAndLiveOwnerNotOnlySameUuid() {
        var f = new Fixture(ContractReviewStepType.TERM);
        var execution = f.claim("worker-a");
        f.now.set(NOW.plusSeconds(60));
        assertThatThrownBy(() -> f.coordinator.complete(execution, f.emptyResult(execution)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(f.completes);
        var step = f.step.get();
        f.step.set(new ContractReviewStep(step.stepId(), step.runId(), step.stepType(), true,
                step.status(), step.stepPolicyVersion(), 2, null, null, null, NOW, null, 2));
        f.now.set(NOW.plusSeconds(1));
        assertThatThrownBy(() -> f.coordinator.close(execution, ContractReviewStepStatus.FAILED, "SAFE_FAILURE"))
                .isInstanceOf(BusinessException.class);
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    @Test void validLeaseIsNotRecoveredAndExpiredNotStartedKeepsOperationIdentity() {
        var f = new Fixture(ContractReviewStepType.BREACH);
        var original = f.claim("worker-a");
        var candidate = f.currentCandidate();
        assertThat(f.coordinator.recover(candidate, "worker-b")).isEmpty();
        assertThat(f.attempt.get()).isEqualTo(original.attempt());
        f.now.set(NOW.plusSeconds(60));
        var recovered = f.coordinator.recover(candidate, "worker-b").orElseThrow();
        assertThat(recovered.attempt().attemptId()).isEqualTo(original.attempt().attemptId());
        assertThat(recovered.attempt().operationIdentity()).isEqualTo(original.attempt().operationIdentity());
        assertThat(recovered.attempt().leaseOwner()).isEqualTo("worker-b");
        assertThatThrownBy(() -> f.coordinator.complete(original, f.emptyResult(original)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void requestedExternalCallWithExpiredLeaseBecomesUnknownAndRetainsOperation() {
        var f = new Fixture(ContractReviewStepType.SEMANTIC_AI);
        var execution = f.claim("worker-a");
        var requested = f.coordinator.markRequested(execution, "b".repeat(64));
        assertThat(requested.attempt().externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        f.now.set(NOW.plusSeconds(60));
        assertThat(f.coordinator.recover(f.currentCandidate(), "worker-b")).isEmpty();
        assertThat(f.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.run.get().status()).isEqualTo(ContractReviewRunStatus.UNKNOWN);
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                execution.command().idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test void freshCoordinatorCanRediscoverSameDurablePendingAttempt() {
        var f = new Fixture(ContractReviewStepType.TERM);
        var before = f.candidate;
        f.coordinator = f.coordinator();
        var execution = f.claim("worker-restarted");
        assertThat(execution.attempt().attemptId()).isEqualTo(before.attemptId());
        assertThat(execution.run().requestedBy()).isEqualTo("initiator-1");
    }

    @Test void legacySynchronousStartCannotReplaceAValidWorkerClaim() {
        var f = new Fixture(ContractReviewStepType.BREACH);
        var execution = f.claim("worker-a");
        var legacy = new ReviewStepStartTransactionCoordinator(f.runs, f.steps, f.attempts,
                f.idempotency, f.events::add, f.policies(), Clock.fixed(NOW, ZoneOffset.UTC));
        var command = new ReviewStepStartTransactionCoordinator.StartCommand(execution.run().projectId(),
                execution.run().contractId(), execution.run().runId(), execution.step().stepId(),
                "browser-compatibility-key", execution.command().metadata());
        assertThatThrownBy(() -> legacy.start(command, execution.actor())).isInstanceOf(BusinessException.class);
        assertThat(f.attempt.get()).isEqualTo(execution.attempt());
        assertThat(f.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                command.idempotencyKey())).isEmpty();
        verifyNoInteractions(f.completes);
    }

    /** Test-only repository fixture; real-platform transaction/lock behavior remains NOT VERIFIED. */
    static final class Fixture {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final AtomicReference<ContractReviewRun> run;
        final AtomicReference<ContractReviewStep> step;
        final AtomicReference<ContractReviewStepAttempt> attempt;
        final ContractReviewRunRepository runs = mock(ContractReviewRunRepository.class);
        final ContractReviewStepRepository steps = mock(ContractReviewStepRepository.class);
        final ContractReviewStepAttemptRepository attempts = mock(ContractReviewStepAttemptRepository.class);
        final ContractReviewWorkerRepository workers = mock(ContractReviewWorkerRepository.class);
        final FrozenReviewInputLoader inputs = mock(FrozenReviewInputLoader.class);
        final ReviewStepCompleteTransactionCoordinator completes = mock(ReviewStepCompleteTransactionCoordinator.class);
        final ReviewStepOutputReadService outputs = mock(ReviewStepOutputReadService.class);
        final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        final com.fuquan.audit.contract.infra.memory.project.InMemoryContractModelCallAuditRepository modelCalls =
                new com.fuquan.audit.contract.infra.memory.project.InMemoryContractModelCallAuditRepository();
        final List<OperationAuditEvent> events = new CopyOnWriteArrayList<>();
        final ContractReviewWorkerRepository.Candidate candidate;
        final List<ContractRuleConfiguration> configurations = List.of(new ContractRuleConfiguration(
                F02RuleCodes.AWARD_AMOUNT_MATCH, "Frozen award amount", true,
                RiskLevel.YELLOW, false, 9, "test-fixture", NOW));
        ReviewWorkerTransactionCoordinator coordinator;
        SemanticCheckpointRepository semanticCheckpoints;

        Fixture(ContractReviewStepType type) {
            UUID runId = UUID.randomUUID(), stepId = UUID.randomUUID(), attemptId = UUID.randomUUID();
            run = new AtomicReference<>(new ContractReviewRun(runId, UUID.randomUUID(), UUID.randomUUID(),
                    0, null, null, 1, "{}", ContractSnapshotCanonicalizer.sha256("{}"),
                    ContractReviewExecutionPolicyResolver.REVIEW_PROFILE_VERSION, policies().resolveCurrentF02().ruleVersion(),
                    ContractReviewExecutionPolicyResolver.MODEL_POLICY_VERSION, "a".repeat(64),
                    "initiator-1", ContractReviewRunStatus.PENDING, NOW, null, null, null, null, null, 0));
            String policy = type == ContractReviewStepType.BREACH ? ReviewRunManifestFactory.BREACH_POLICY_VERSION
                    : type == ContractReviewStepType.TERM ? ReviewRunManifestFactory.TERM_POLICY_VERSION
                    : type == ContractReviewStepType.F02_DETERMINISTIC ? run.get().ruleVersion()
                    : ContractReviewExecutionPolicyResolver.MODEL_POLICY_VERSION;
            step = new AtomicReference<>(new ContractReviewStep(stepId, runId, type, true,
                    ContractReviewStepStatus.PENDING, policy, 1, null, null, null, null, null, 0));
            attempt = new AtomicReference<>(new ContractReviewStepAttempt(attemptId, runId, stepId, 1,
                    "c".repeat(64), ContractReviewStepStatus.PENDING, null, null, null,
                    ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, null, null, null, null, null, 0));
            candidate = currentCandidate();
            when(runs.findByIdForUpdate(runId)).thenAnswer(call -> Optional.of(run.get()));
            when(runs.transition(any(), any())).thenAnswer(call -> {
                var expected = call.<ContractReviewRun>getArgument(0); var next = call.<ContractReviewRun>getArgument(1);
                ReviewStepTransitionGuard.requireValid(expected, next);
                if (!run.compareAndSet(expected, next)) throw ReviewWorkerLeasePolicy.conflict(); return next;
            });
            when(steps.findStepById(stepId)).thenAnswer(call -> Optional.of(step.get()));
            when(steps.transition(any(), any())).thenAnswer(call -> {
                var expected = call.<ContractReviewStep>getArgument(0); var next = call.<ContractReviewStep>getArgument(1);
                ReviewStepTransitionGuard.requireValid(expected, next);
                if (!step.compareAndSet(expected, next)) throw ReviewWorkerLeasePolicy.conflict(); return next;
            });
            when(steps.findStepsByRunId(runId)).thenAnswer(call -> {
                var manifest = new ReviewRunManifestFactory().create(runId, NOW).steps();
                return manifest.stream().map(value -> value.stepType() == type ? step.get() : value).toList();
            });
            when(attempts.findAttemptById(attemptId)).thenAnswer(call -> Optional.of(attempt.get()));
            when(attempts.transition(any(), any())).thenAnswer(call -> {
                var expected = call.<ContractReviewStepAttempt>getArgument(0); var next = call.<ContractReviewStepAttempt>getArgument(1);
                ReviewStepTransitionGuard.requireValid(expected, next);
                if (!attempt.compareAndSet(expected, next)) throw ReviewWorkerLeasePolicy.conflict(); return next;
            });
            when(workers.compareAndSetAttempt(any(), any(), any(), any())).thenAnswer(call -> {
                var expected = call.<ContractReviewStepAttempt>getArgument(0);
                var next = call.<ContractReviewStepAttempt>getArgument(1);
                if (!attempt.compareAndSet(expected, next)) throw ReviewWorkerLeasePolicy.conflict(); return next;
            });
            when(workers.findBusinessInitiator("initiator-1")).thenReturn(Optional.of(
                    new ContractReviewWorkerRepository.BusinessInitiator("initiator-1", "auditor", "Audit User")));
            when(inputs.load(any(), any(), any())).thenAnswer(call -> new FrozenReviewInput(run.get(),
                    com.fuquan.audit.contract.infra.memory.LocalContractFixtures.contracts().get(0), List.of()));
            coordinator = coordinator();
        }
        ReviewWorkerTransactionCoordinator coordinator() {
            return coordinator(completes, outputs);
        }
        ContractReviewExecutionPolicyResolver policies() {
            return new ContractReviewExecutionPolicyResolver(() -> configurations, new F02RulePolicyFingerprint());
        }
        ReviewWorkerTransactionCoordinator coordinator(ReviewStepCompleteTransactionCoordinator completion,
                ReviewStepOutputReadService outputReader) {
            return coordinator(completion, outputReader, null);
        }
        ReviewWorkerTransactionCoordinator coordinator(ReviewStepCompleteTransactionCoordinator completion,
                ReviewStepOutputReadService outputReader, FrozenF02ResultMapper f02Mapper) {
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return now.get(); }
            };
            return new ReviewWorkerTransactionCoordinator(workers, runs, steps, attempts, idempotency,
                    events::add, policies(), inputs, completion, outputReader,
                    new ReviewRunStatusAggregator(), clock, Duration.ofSeconds(60), f02Mapper, modelCalls, semanticCheckpoints);
        }
        ContractReviewWorkerRepository.Candidate currentCandidate() {
            var value = attempt.get();
            return new ContractReviewWorkerRepository.Candidate(value.runId(), value.stepId(), value.attemptId(), value.versionNo());
        }
        ReviewStepStartTransactionCoordinator.StartedExecution claim(String owner) {
            return coordinator.claim(candidate, owner);
        }
        ReviewStepStartTransactionCoordinator.StartedExecution tryClaim(String owner) {
            try { return claim(owner); } catch (BusinessException conflict) { return null; }
        }
        StepComputationResult emptyResult(ReviewStepStartTransactionCoordinator.StartedExecution execution) {
            return new StepComputationResult("REVIEW_FINDING_SET", execution.attempt().attemptId(),
                    List.of(), List.of(), null, null, null);
        }
    }
}
