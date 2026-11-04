package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.project.factai.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.util.concurrent.locks.ReentrantLock;

class SemanticCheckpointCoordinatorTest {
    static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    static final String INPUT = "a".repeat(64), POLICY = "b".repeat(64), KEY = "c".repeat(64), FINAL = "d".repeat(64);

    @Test void restartReusesVerifiedUnitsAndTransfersOnlyOwnershipWithoutResettingBudgets() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        f.done(access, sent);
        f.replaceAttempt("worker-b", 2);
        var recovered = f.open();
        assertThat(recovered.planId()).isEqualTo(plan.planId());
        assertThat(recovered.originalAttemptId()).isEqualTo(plan.originalAttemptId());
        assertThat(recovered.currentAttemptId()).isEqualTo(f.parent.attemptId());
        assertThat(recovered.fence()).isGreaterThan(plan.fence());
        assertThat(recovered.usedCalls()).isEqualTo(1);
        assertThat(recovered.usedMs()).isEqualTo(7);
        assertThat(recovered.spec().deadlineAt()).isEqualTo(plan.spec().deadlineAt());
        assertThat(f.service.findUnits(plan.planId())).filteredOn(u -> u.logicalKey().equals(KEY))
                .singleElement().extracting(Unit::status).isEqualTo(UnitStatus.VERIFIED);
        assertThatThrownBy(() -> f.sent(access, FINAL, Purpose.SYNTHESIZE)).isInstanceOf(IllegalStateException.class);
    }

    @Test void sentBeforeCrashBecomesUnknownAndReservationCannotDisappearOrBeResent() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        f.sent(access, KEY, Purpose.ANALYZE);
        f.replaceAttempt("worker-b", 2);
        var blocked = f.open();
        assertThat(blocked.reservedCalls()).isEqualTo(1);
        assertThat(blocked.reservedMs()).isEqualTo(100);
        assertThat(f.service.findUnits(plan.planId()).get(0).status()).isEqualTo(UnitStatus.UNKNOWN);
        assertThatThrownBy(() -> f.sent(f.access(blocked), KEY, Purpose.ANALYZE)).isInstanceOf(IllegalStateException.class);
        assertThat(f.audits).singleElement().satisfies(call -> {
            assertThat(call.reviewStepAttemptId()).isEqualTo(plan.originalAttemptId());
            assertThat(call.safeReason()).isEqualTo("TRANSPORT_OUTCOME_UNKNOWN");
        });
        f.open();
        assertThat(f.audits).hasSize(1);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
    }

    @Test void unknownUnitRejectsLateSuccessfulCompletionAndKeepsItsReservationAndAudit() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        var unknown = f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN", 15, f.usage);
        var reserved = f.repo.plan;
        assertThatThrownBy(() -> f.service.completeUnit(access, sent.executionId(), UnitStatus.VERIFIED,
                "{}", digest("{}"), 15, f.usage, null)).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan).isEqualTo(reserved);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
        assertThat(f.repo.plan.reservedMs()).isEqualTo(100);
        assertThat(f.service.findUnits(plan.planId())).contains(unknown);
        assertThat(f.audits).singleElement().satisfies(call -> {
            assertThat(call.safeReason()).isEqualTo("TRANSPORT_OUTCOME_UNKNOWN");
            assertThat(call.reviewStepAttemptId()).isEqualTo(plan.originalAttemptId());
        });
    }


    @Test void staleOwnerExpiredLeaseWrongParentAndChangedPolicyAreRejected() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        f.now.set(NOW.plusSeconds(61));
        assertThatThrownBy(() -> f.sent(access, KEY, Purpose.ANALYZE)).isInstanceOf(IllegalStateException.class);
        f.now.set(NOW);
        when(f.step.runId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(f::open).isInstanceOf(IllegalStateException.class);
        when(f.step.runId()).thenReturn(f.parent.runId());
        assertThatThrownBy(() -> f.service.openOrClaim(f.parent,
                new PlanSpec(INPUT, "e".repeat(64), "{}", NOW.plusSeconds(30), 1000, 3, 1), f.specs))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void checkpointCapacityFailureAtomicallyPersistsActualUsageAndSpendsOnlyOnce() {
        var f = new Fixture();
        var plan = f.service.openOrClaim(f.parent,
                new PlanSpec(INPUT, POLICY, "{}", NOW.plusSeconds(900), 900000, 3, 1), f.specs);
        var access = f.access(plan);
        var sent = f.service.reserveAndMarkSent(access, KEY, Purpose.ANALYZE, INPUT, 300000,
                new CallMetadata(UUID.randomUUID(), "synthetic", "synthetic", INPUT));
        String reason = "SEMANTIC_CHECKPOINT_OUTPUT_BOUND";
        String receipt = "{\"status\":\"FAILED\",\"call\":{\"outcome\":\"FAILED\",\"safeReason\":\"" + reason + "\"}}";
        var usage = new CallUsage("STOP", 100, 200, 300);
        doThrow(new IllegalStateException("synthetic audit rollback")).when(f.audit).insertAll(anyList());
        assertThatThrownBy(() -> f.service.completeUnit(access, sent.executionId(), UnitStatus.FAILED,
                receipt, digest(receipt), 1234, usage, reason)).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.units.get(sent.executionId()).status()).isEqualTo(UnitStatus.SENT);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
        assertThat(f.repo.plan.usedCalls()).isZero();
        doAnswer(call -> { f.audits.addAll(call.getArgument(0)); return null; }).when(f.audit).insertAll(anyList());
        var completed = f.service.completeUnit(access, sent.executionId(), UnitStatus.FAILED,
                receipt, digest(receipt), 1234, usage, reason);
        assertThat(completed.status()).isEqualTo(UnitStatus.FAILED);
        assertThat(completed.resultJson()).isEqualTo(receipt);
        assertThat(f.repo.plan.reservedCalls()).isZero();
        assertThat(f.repo.plan.reservedMs()).isZero();
        assertThat(f.repo.plan.usedCalls()).isEqualTo(1);
        assertThat(f.repo.plan.usedMs()).isEqualTo(1234);
        assertThat(f.service.completeUnit(access, sent.executionId(), UnitStatus.FAILED,
                receipt, digest(receipt), 1234, usage, reason)).isEqualTo(completed);
        assertThat(f.audits).singleElement().satisfies(call -> {
            assertThat(call.safeReason()).isEqualTo(reason);
            assertThat(call.outcome().name()).isEqualTo("FAILED");
            assertThat(call.durationMs()).isEqualTo(1234);
            assertThat(call.finishReason()).isEqualTo("STOP");
            assertThat(call.promptTokens()).isEqualTo(100);
            assertThat(call.completionTokens()).isEqualTo(200);
            assertThat(call.totalTokens()).isEqualTo(300);
        });
        assertThatThrownBy(() -> f.sent(access, KEY, Purpose.REPAIR)).isInstanceOf(IllegalStateException.class);
    }

    @Test void observedPythonUnknownRetainsDurationUsageAndReservationsWithoutReplayMutation() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        var usage = new CallUsage("LENGTH", 101, 2, 103);
        var unknown = f.service.markUnknown(access, sent.executionId(), "MODEL_TIMEOUT_UNKNOWN", 120001, usage);
        assertThat(unknown.status()).isEqualTo(UnitStatus.UNKNOWN);
        assertThat(unknown.durationMs()).isEqualTo(120001);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
        assertThat(f.repo.plan.reservedMs()).isEqualTo(100);
        assertThat(f.repo.plan.usedCalls()).isZero();
        assertThat(f.repo.plan.usedMs()).isZero();
        assertThat(f.audits).singleElement().satisfies(call -> {
            assertThat(call.durationMs()).isEqualTo(120001);
            assertThat(call.finishReason()).isEqualTo("LENGTH");
            assertThat(call.promptTokens()).isEqualTo(101);
            assertThat(call.completionTokens()).isEqualTo(2);
            assertThat(call.totalTokens()).isEqualTo(103);
        });
        assertThat(f.service.markUnknown(access, sent.executionId(), "MODEL_TIMEOUT_UNKNOWN", 120001, usage))
                .isEqualTo(unknown);
        assertThatThrownBy(() -> f.service.markUnknown(access, sent.executionId(), "MODEL_TIMEOUT_UNKNOWN", 120001,
                new CallUsage("LENGTH", 101, 3, 104))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.service.markUnknown(access, sent.executionId(), "MODEL_TIMEOUT_UNKNOWN", 120002, usage))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN", 120001, usage))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.sent(access, KEY, Purpose.REPAIR)).isInstanceOf(IllegalStateException.class);
        assertThat(f.audits).hasSize(1);
    }

    @Test void crashUnknownKeepsZeroUnknownTelemetryAndAuditRollbackPreservesSent() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        doThrow(new IllegalStateException("synthetic audit rollback")).when(f.audit).insertAll(anyList());
        assertThatThrownBy(() -> f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.units.get(sent.executionId()).status()).isEqualTo(UnitStatus.SENT);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
        assertThat(f.audits).isEmpty();
        doAnswer(call -> { f.audits.addAll(call.getArgument(0)); return null; }).when(f.audit).insertAll(anyList());
        var unknown = f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN");
        assertThat(unknown.durationMs()).isZero();
        assertThat(f.audits).singleElement().satisfies(call -> {
            assertThat(call.durationMs()).isZero();
            assertThat(call.finishReason()).isNull();
            assertThat(call.promptTokens()).isNull();
            assertThat(call.completionTokens()).isNull();
            assertThat(call.totalTokens()).isNull();
        });
        assertThat(f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN")).isEqualTo(unknown);
        assertThatThrownBy(() -> f.service.markUnknown(access, sent.executionId(), "TRANSPORT_OUTCOME_UNKNOWN", -1, f.usage))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.audits).hasSize(1);
    }

    @Test void parentHeartbeatKeepsLiveOwnerAbleToPersistAnAlreadySentResult() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        f.now.set(NOW.plusSeconds(61));
        var prior = f.attempts.findAttemptById(f.parent.attemptId()).orElseThrow();
        when(f.attempts.findAttemptById(f.parent.attemptId())).thenReturn(Optional.of(
                new ContractReviewStepAttempt(prior.attemptId(), prior.runId(), prior.stepId(), prior.attemptNo(),
                        prior.operationIdentity(), prior.status(), prior.leaseOwner(), NOW.plusSeconds(120),
                        prior.claimedAt(), prior.externalCallState(), prior.externalRequestFingerprint(), null,
                        prior.provider(), prior.model(), prior.startedAt(), null, null, null, null, prior.versionNo() + 1)));
        assertThat(f.done(access, sent).status()).isEqualTo(UnitStatus.VERIFIED);
        assertThat(f.service.findPlan(f.parent.stepId()).orElseThrow().leaseUntil()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test void deadlineCannotBeWashedByAttemptTransferOrByFinalPublication() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        f.done(access, f.sent(access, KEY, Purpose.ANALYZE));
        f.done(access, f.sent(access, FINAL, Purpose.SYNTHESIZE));
        f.now.set(NOW.plusSeconds(2));
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        f.replaceAttempt("worker-b", 2);
        var resumed = f.open();
        assertThat(resumed.spec().deadlineAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(resumed.usedCalls()).isEqualTo(2);
        assertThatThrownBy(() -> f.service.openOrClaim(f.parent,
                new PlanSpec(INPUT, POLICY, "{}", NOW.plusSeconds(3), 1000, 3, 1), f.specs))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void threeVerifiedAnalysisUnitsReopenWithoutNewCallsOrCopyingAuditsToNewAttempt() {
        var f = new Fixture();
        String a = "1".repeat(64), b = "2".repeat(64), c = "3".repeat(64);
        var units = List.of(new UnitSpec(a, 1, Purpose.ANALYZE, INPUT, List.of()),
                new UnitSpec(b, 2, Purpose.ANALYZE, INPUT, List.of()),
                new UnitSpec(c, 3, Purpose.ANALYZE, INPUT, List.of()),
                new UnitSpec(FINAL, 4, Purpose.SYNTHESIZE, INPUT, List.of(a, b, c)));
        var spec = new PlanSpec(INPUT, POLICY, "{}", NOW.plusSeconds(1), 1000, 4, 0);
        var plan = f.service.openOrClaim(f.parent, spec, units); var access = f.access(plan);
        for (String key : List.of(a, b, c)) f.done(access, f.sent(access, key, Purpose.ANALYZE));
        f.replaceAttempt("worker-b", 2);
        var resumed = f.service.openOrClaim(f.parent, spec, units);
        assertThat(resumed.usedCalls()).isEqualTo(3);
        assertThat(f.service.findUnits(plan.planId()).stream().filter(u -> u.status() == UnitStatus.VERIFIED).count()).isEqualTo(3);
        assertThat(f.audits).hasSize(3).allSatisfy(call -> assertThat(call.reviewStepAttemptId()).isEqualTo(plan.originalAttemptId()));
        assertThat(f.audit.findByReviewStepAttempt(f.parent.attemptId())).isEmpty();
    }

    @Test void callsAndRepairsShareBudgetAndRepeatedCompletionCannotSpendTwiceOrReplaceResult() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var first = f.sent(access, KEY, Purpose.ANALYZE);
        f.service.completeUnit(access, first.executionId(), UnitStatus.REPAIR_REQUIRED, "{}", digest("{}"), 7,
                f.usage, "INVALID_TYPED_SCHEMA");
        var repair = f.sent(access, KEY, Purpose.REPAIR);
        f.done(access, repair); f.done(access, repair);
        var current = f.service.findPlan(f.parent.stepId()).orElseThrow();
        assertThat(current.usedCalls()).isEqualTo(2);
        assertThat(current.usedRepairs()).isEqualTo(1);
        assertThat(current.reservedCalls()).isZero();
        assertThat(f.audits).hasSize(2);
        assertThatThrownBy(() -> f.service.completeUnit(access, repair.executionId(), UnitStatus.VERIFIED,
                "[]", digest("[]"), 7, f.usage, null)).isInstanceOf(IllegalStateException.class);
        var synthesis = f.sent(access, FINAL, Purpose.SYNTHESIZE); f.done(access, synthesis);
        assertThat(f.service.markComplete(access, digest("{}" )).status()).isEqualTo(PlanStatus.COMPLETE);
    }

    @Test void dependenciesAndFinalSynthesisFencePublicationAndJsonIsBoundedAndHashed() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        assertThatThrownBy(() -> f.sent(access, FINAL, Purpose.SYNTHESIZE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.service.markComplete(access, digest("{}"))).isInstanceOf(IllegalStateException.class);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        assertThatThrownBy(() -> f.service.completeUnit(access, sent.executionId(), UnitStatus.VERIFIED,
                "{}", INPUT, 7, f.usage, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanSpec(INPUT, POLICY, "\"" + "中".repeat(100000) + "\"",
                NOW.plusSeconds(30), 1000, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanSpec(INPUT, POLICY, "{} trailing-private-data",
                NOW.plusSeconds(1), 1000, 3, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void simultaneousReservationsOnlyOneCanBecomeSent() throws Exception {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<Boolean> reserve = () -> { start.await();
                try { f.sent(access, KEY, Purpose.ANALYZE); return true; }
                catch (IllegalStateException rejected) { return false; }
            };
            var a = pool.submit(reserve); var b = pool.submit(reserve); start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
            assertThat(f.service.findPlan(f.parent.stepId()).orElseThrow().reservedCalls()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void transactionRollbackRestoresBothBudgetAndResultWhenAuditInsertFails() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        doThrow(new IllegalStateException("synthetic audit rollback")).when(f.audit).insertAll(anyList());
        assertThatThrownBy(() -> f.done(access, sent)).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan.reservedCalls()).isEqualTo(1);
        assertThat(f.repo.units.get(sent.executionId()).status()).isEqualTo(UnitStatus.SENT);
    }

    @Test void replayRequiresIdenticalUsageAndDurableProviderCallIdentity() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE); f.done(access, sent);
        assertThatThrownBy(() -> f.service.completeUnit(access, sent.executionId(), UnitStatus.VERIFIED,
                "{}", digest("{}"), 7, new CallUsage("STOP", 10, 3, 13), null))
                .isInstanceOf(IllegalStateException.class);
        var old = f.audits.get(0);
        f.audits.set(0, new ContractModelCallAudit(old.callAuditId(), null, old.reviewStepAttemptId(),
                old.attemptNo(), null, old.callOrdinal(), old.phase(), "e".repeat(64), "different-provider",
                old.modelName(), old.outcome(), old.safeReason(), old.durationMs(), old.finishReason(),
                old.promptTokens(), old.completionTokens(), old.totalTokens(), null, old.createdAt()));
        assertThatThrownBy(() -> f.done(access, sent)).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan.usedCalls()).isEqualTo(1);
        assertThat(f.audits).hasSize(1);
    }

    @Test void repairQuotaCallQuotaAndConsumedTimeEachPreventAnotherDispatch() {
        for (String limit : List.of("repair", "call", "time")) {
            var f = new Fixture();
            var spec = new PlanSpec(INPUT, POLICY, "{}", NOW.plusMillis(1000), 1000,
                    limit.equals("call") ? 1 : 3, limit.equals("repair") ? 0 : 1);
            var plan = f.service.openOrClaim(f.parent, spec, f.specs); var access = f.access(plan);
            var sent = f.sent(access, KEY, Purpose.ANALYZE);
            f.service.completeUnit(access, sent.executionId(), UnitStatus.REPAIR_REQUIRED, "{}", digest("{}"),
                    limit.equals("time") ? 950 : 7, f.usage, "INVALID_TYPED_SCHEMA");
            assertThatThrownBy(() -> f.sent(access, KEY, Purpose.REPAIR)).isInstanceOf(IllegalStateException.class);
            assertThat(f.repo.plan.reservedCalls()).isZero();
            assertThat(f.repo.plan.usedCalls()).isEqualTo(1);
            assertThat(f.audits).hasSize(1);
        }
    }

    @Test void sameOwnerReclaimIncrementsFenceAndOldFenceCannotMutate() {
        var f = new Fixture(); var first = f.open(); var oldAccess = f.access(first);
        var reopened = f.open();
        assertThat(reopened.fence()).isGreaterThan(first.fence());
        assertThatThrownBy(() -> f.sent(oldAccess, KEY, Purpose.ANALYZE)).isInstanceOf(IllegalStateException.class);
        assertThat(f.repo.plan.reservedCalls()).isZero();
    }

    @Test void completedPlanCanTransferForFinalPublicationRecoveryWithoutNewCalls() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        f.done(access, f.sent(access, KEY, Purpose.ANALYZE));
        f.done(access, f.sent(access, FINAL, Purpose.SYNTHESIZE));
        var complete = f.service.markComplete(access, digest("{}"));
        f.replaceAttempt("worker-b", 2);
        var resumed = f.open();
        assertThat(resumed.status()).isEqualTo(PlanStatus.COMPLETE);
        assertThat(resumed.currentAttemptId()).isEqualTo(f.parent.attemptId());
        assertThat(resumed.fence()).isGreaterThan(complete.fence());
        assertThat(resumed.finalResultSha256()).isEqualTo(complete.finalResultSha256());
        assertThat(resumed.usedCalls()).isEqualTo(2);
        assertThat(f.service.markComplete(f.access(resumed), digest("{}"))).isEqualTo(resumed);
        assertThat(f.audits).hasSize(2);
    }

    @Test void failedUnitOnlyRetriesUnderExplicitLaterAttemptAndKeepsOriginalBudget() {
        var f = new Fixture(); var plan = f.open(); var access = f.access(plan);
        var sent = f.sent(access, KEY, Purpose.ANALYZE);
        f.service.completeUnit(access, sent.executionId(), UnitStatus.FAILED, null, null, 7,
                f.usage, "PROVIDER_429");
        assertThatThrownBy(() -> f.sent(access, KEY, Purpose.REPAIR)).isInstanceOf(IllegalStateException.class);
        var old = f.attempts.findAttemptById(f.parent.attemptId()).orElseThrow();
        when(f.attempts.findAttemptById(old.attemptId())).thenReturn(Optional.of(new ContractReviewStepAttempt(
                old.attemptId(), old.runId(), old.stepId(), old.attemptNo(), old.operationIdentity(),
                ContractReviewStepStatus.FAILED, old.leaseOwner(), old.leaseUntil(), old.claimedAt(),
                old.externalCallState(), old.externalRequestFingerprint(), null, old.provider(), old.model(),
                old.startedAt(), NOW, "PROVIDER_429", null, null, old.versionNo() + 1)));
        f.replaceAttempt("worker-b", 2);
        var resumed = f.open(); var next = f.sent(f.access(resumed), KEY, Purpose.REPAIR);
        assertThat(next.executionNo()).isEqualTo(2);
        assertThat(next.attemptId()).isEqualTo(f.parent.attemptId());
        assertThat(resumed.usedCalls()).isEqualTo(1);
        assertThat(resumed.spec().deadlineAt()).isEqualTo(plan.spec().deadlineAt());
        assertThat(f.service.findUnits(plan.planId())).filteredOn(u -> u.executionId().equals(sent.executionId()))
                .singleElement().extracting(Unit::status).isEqualTo(UnitStatus.FAILED);
    }

    /** Enlists fake resources through Spring's real transaction interceptor. */
    static class FakeTransactions extends AbstractPlatformTransactionManager {
        final Memory repo; final List<ContractModelCallAudit> audits;
        final ReentrantLock lock = new ReentrantLock();
        final ThreadLocal<Runnable> rollback = new ThreadLocal<>();
        FakeTransactions(Memory repo, List<ContractModelCallAudit> audits) { this.repo = repo; this.audits = audits; }
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object ignored, TransactionDefinition definition) {
            lock.lock(); var plan = repo.plan; var units = new LinkedHashMap<>(repo.units);
            var savedAudits = List.copyOf(audits);
            rollback.set(() -> { repo.plan = plan; repo.units = units; audits.clear(); audits.addAll(savedAudits); });
        }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { rollback.get().run(); }
        protected void doCleanupAfterCompletion(Object transaction) { rollback.remove(); lock.unlock(); }
    }

    static class Memory implements SemanticCheckpointRepository {
        Plan plan; Map<UUID, Unit> units = new LinkedHashMap<>();
        public Optional<Plan> findPlanByStep(UUID id) { return Optional.ofNullable(plan).filter(p -> p.stepId().equals(id)); }
        public Optional<Plan> lockPlanByStep(UUID id) { return findPlanByStep(id); }
        public void lockParentAttempt(UUID id) { }
        public List<Unit> findUnits(UUID id) { return units.values().stream().filter(u -> u.planId().equals(id)).toList(); }
        public void insertPlan(Plan p) { if (plan != null) throw new IllegalStateException(); plan = p; }
        public void insertUnit(Unit u) { if (units.putIfAbsent(u.executionId(), u) != null) throw new IllegalStateException(); }
        public void compareAndSetPlan(Plan expected, Plan next) { if (!Objects.equals(plan, expected)) throw new IllegalStateException(); plan = next; }
        public void compareAndSetUnit(Unit expected, Unit next) { if (!Objects.equals(units.get(expected.executionId()), expected)) throw new IllegalStateException(); units.put(next.executionId(), next); }
    }
    static class Fixture {
        final Memory repo = new Memory(); final List<ContractModelCallAudit> audits = new ArrayList<>();
        final ContractModelCallAuditRepository audit = mock(ContractModelCallAuditRepository.class);
        final ContractReviewRunRepository runs = mock(ContractReviewRunRepository.class);
        final ContractReviewStepRepository steps = mock(ContractReviewStepRepository.class);
        final ContractReviewStepAttemptRepository attempts = mock(ContractReviewStepAttemptRepository.class);
        final ContractReviewStep step = mock(ContractReviewStep.class);
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final Clock clock = mock(Clock.class);
        Parent parent = new Parent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "worker-a");
        final List<UnitSpec> specs = List.of(new UnitSpec(KEY, 1, Purpose.ANALYZE, INPUT, List.of()),
                new UnitSpec(FINAL, 2, Purpose.SYNTHESIZE, INPUT, List.of(KEY)));
        final PlanSpec spec = new PlanSpec(INPUT, POLICY, "{}", NOW.plusSeconds(1), 1000, 3, 1);
        final CallUsage usage = new CallUsage("STOP", 10, 2, 12);
        final SemanticCheckpointCoordinator service;
        Fixture() {
            var run = mock(ContractReviewRun.class); when(run.runId()).thenReturn(parent.runId());
            when(run.status()).thenReturn(ContractReviewRunStatus.RUNNING);
            when(runs.findByIdForUpdate(parent.runId())).thenReturn(Optional.of(run));
            when(step.stepId()).thenReturn(parent.stepId()); when(step.runId()).thenReturn(parent.runId());
            when(step.stepType()).thenReturn(ContractReviewStepType.SEMANTIC_AI);
            when(step.status()).thenReturn(ContractReviewStepStatus.RUNNING);
            when(steps.findStepById(parent.stepId())).thenReturn(Optional.of(step));
            when(clock.instant()).thenAnswer(call -> now.get());
            doAnswer(call -> { audits.addAll(call.getArgument(0)); return null; }).when(audit).insertAll(anyList());
            when(audit.findByReviewStepAttempt(any())).thenAnswer(call -> audits.stream()
                    .filter(a -> a.reviewStepAttemptId().equals(call.getArgument(0))).toList());
            setAttempt(1);
            var proxy = new ProxyFactory(new SemanticCheckpointCoordinator(repo, runs, steps, attempts, audit, clock));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new FakeTransactions(repo, audits),
                    new AnnotationTransactionAttributeSource()));
            service = (SemanticCheckpointCoordinator) proxy.getProxy();
        }
        void replaceAttempt(String owner, int number) { parent = new Parent(parent.runId(), parent.stepId(), UUID.randomUUID(), owner); setAttempt(number); }
        void setAttempt(int number) {
            var a = new ContractReviewStepAttempt(parent.attemptId(), parent.runId(), parent.stepId(), number,
                    INPUT, ContractReviewStepStatus.RUNNING, parent.leaseOwner(), NOW.plusSeconds(60), NOW,
                    ReviewStepExternalCallState.REQUESTED, INPUT, null, "synthetic", "synthetic", NOW,
                    null, null, null, null, 1);
            when(attempts.findAttemptById(parent.attemptId())).thenReturn(Optional.of(a));
            when(step.currentAttemptNo()).thenReturn(number);
        }
        Plan open() { return service.openOrClaim(parent, spec, specs); }
        Access access(Plan p) { return new Access(parent, p.planId(), p.fence()); }
        Unit sent(Access access, String key, Purpose purpose) { return service.reserveAndMarkSent(access, key, purpose,
                INPUT, 100, new CallMetadata(UUID.randomUUID(), "synthetic", "synthetic", INPUT)); }
        Unit done(Access access, Unit unit) { return service.completeUnit(access, unit.executionId(),
                UnitStatus.VERIFIED, "{}", digest("{}"), 7, usage, null); }
    }
}
