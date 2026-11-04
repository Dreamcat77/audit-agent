package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.observability.*;
import com.fuquan.audit.contract.application.*;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewResultsRepositories;
import com.fuquan.audit.review.infra.memory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReviewWorkerF02RecoveryTest {
    @Test void oldFrozenPolicyInnerSuccessClosesUnknownWithoutReprojectionOrAnotherTask() {
        var f = navigationV2Fixture();
        var started = f.worker.claim("technical-worker-a");
        var computed = f.executor.execute(runningInput(f.input, started.run()), new ReviewExecutionContext(
                started.run(), started.step(), started.attempt(), started.actor(), started.command().metadata(),
                started.f02Configurations(), value -> { }));
        var originalTask = f.tasks.findById(computed.outputResourceId()).orElseThrow();
        // Test-only durable rows represent a task completed by the old engine before upgrade.
        // Production immutable Run/Step rows are never migrated or rewritten by this change.
        String legacyPolicy = "f02-policy-v1:" + "e".repeat(64);
        var r = f.worker.run.get();
        f.worker.run.set(new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), r.contractVersion(),
                r.sourceCandidateId(), r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(),
                r.contractSnapshotSha256(), r.reviewProfileVersion(), legacyPolicy, r.modelPolicyVersion(),
                r.inputSnapshotIdentity(), r.requestedBy(), r.status(), r.requestedAt(), r.startedAt(),
                r.completedAt(), r.failedAt(), r.unknownAt(), r.safeReasonCode(), r.versionNo()));
        var s = f.worker.step.get();
        f.worker.step.set(new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), s.required(), s.status(),
                legacyPolicy, s.currentAttemptNo(), s.outputResourceType(), s.outputResourceId(), s.safeReasonCode(),
                s.startedAt(), s.completedAt(), s.versionNo()));
        var oldOperation = f.worker.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                started.command().idempotencyKey()).orElseThrow();
        f.worker.idempotency.rollbackUncommittedClaim(oldOperation);
        f.worker.idempotency.claim(new IdempotencyRequest(oldOperation.scope(), oldOperation.idempotencyKey(),
                oldOperation.actorId(), oldOperation.resourceType(), oldOperation.resourceId(),
                ReviewStepStartTransactionCoordinator.fingerprint(started.actor(), f.worker.run.get(),
                        f.worker.step.get(), f.worker.attempt.get()), oldOperation.resultResourceType(),
                oldOperation.resultResourceId(), started.command().metadata().requestId(), oldOperation.traceId(),
                ReviewWorkerTransactionCoordinatorTest.NOW));
        f.worker.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(60));

        assertThat(f.worker.coordinator.recover(f.worker.currentCandidate(), "technical-worker-restarted")).isEmpty();

        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.worker.attempt.get().safeReasonCode()).isEqualTo("WORKER_F02_PUBLICATION_UNPROVEN");
        assertThat(f.results.findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).containsExactly(originalTask);
    }

    @Test void navigationV2NormalAndInterruptedPublicationPersistTheSameStableCarrierWithoutAnotherTask() {
        var normal = navigationV2Fixture();
        var started = normal.worker.claim("technical-worker-a");
        var computed = normal.executor.execute(runningInput(normal.input, started.run()), new ReviewExecutionContext(started.run(), started.step(),
                started.attempt(), started.actor(), started.command().metadata(), started.f02Configurations(), value -> {}));
        normal.worker.coordinator.complete(started, computed);
        assertThat(normal.results.findFindingsByAttemptId(started.attempt().attemptId()))
                .extracting(ContractReviewFinding::findingId)
                .containsExactly(computed.findings().get(0).findingId());

        var interrupted = navigationV2Fixture();
        var interruptedStart = interrupted.worker.claim("technical-worker-a");
        var interruptedComputed = interrupted.executor.execute(runningInput(interrupted.input, interruptedStart.run()),
                new ReviewExecutionContext(interruptedStart.run(), interruptedStart.step(),
                        interruptedStart.attempt(), interruptedStart.actor(), interruptedStart.command().metadata(),
                        interruptedStart.f02Configurations(), value -> {}));
        interrupted.worker.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(60));
        assertThat(interrupted.worker.coordinator.recover(
                interrupted.worker.currentCandidate(), "technical-worker-restarted")).isEmpty();
        assertThat(interrupted.results.findFindingsByAttemptId(interruptedStart.attempt().attemptId()))
                .extracting(ContractReviewFinding::findingId)
                .containsExactly(interruptedComputed.findings().get(0).findingId());
        assertThat(interrupted.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).hasSize(1);
    }
    @Test void timedOutF02EventuallySucceedsAndExpiredScannerPublishesExactTaskWithoutReexecution() throws Exception {
        var f = fixture();
        ContractReviewWorkerTest.configureDiscovery(f.worker);
        try (var executor = new ContractReviewWorkerTest.StubbornExecutor(ContractReviewStepType.F02_DETERMINISTIC,
                f.executor::execute);
             var worker = ContractReviewWorkerTest.timeoutWorker(f.worker, executor, "technical-worker-a")) {
            worker.scanOnce(4);
            assertThat(executor.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            ContractReviewWorkerTest.assertRunningLease(f.worker);
            var retained = f.worker.attempt.get();
            assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).isEmpty();
            executor.release.countDown();
            assertThat(executor.finished.await(5, TimeUnit.SECONDS)).isTrue();
            var task = f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE).get(0);
            assertThat(f.checks.findByTaskId(task.id())).hasSize(1);
            var innerKey = FrozenF02ReviewStepExecutor.internalKey(retained.runId(), retained.stepId(), retained.attemptId());
            assertThat(f.worker.idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey)
                    .orElseThrow().status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
            worker.scanOnce(4);
            assertThat(f.worker.attempt.get()).isEqualTo(retained);
            f.worker.now.set(retained.leaseUntil());
            try (var restarted = ContractReviewWorkerTest.timeoutWorker(f.worker, executor, "technical-worker-restarted")) {
                restarted.scanOnce(4);
                restarted.scanOnce(4);
            }
            assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
            assertThat(f.worker.step.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
            assertThat(f.worker.attempt.get().outputResourceId()).isEqualTo(task.id());
            assertThat(f.worker.attempt.get().leaseOwner()).isEqualTo("technical-worker-restarted");
            assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).containsExactly(task);
            assertThat(executor.calls.get()).isEqualTo(1);
            assertThat(f.worker.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                    "reviewworker:" + retained.operationIdentity()).orElseThrow().status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        }
    }

    @Test void provenF02CompletionAfterCrashReopensOneRealTaskWithOriginalBusinessInitiator() {
        var f = fixture();
        var started = f.worker.claim("technical-worker-a");
        var computed = f.executor.execute(runningInput(f.input, started.run()), new ReviewExecutionContext(started.run(), started.step(),
                started.attempt(), started.actor(), started.command().metadata(), started.f02Configurations(), value -> {}));
        var task = f.tasks.findById(computed.outputResourceId()).orElseThrow();
        assertThat(task.createdBy()).isEqualTo(started.run().requestedBy()).isNotEqualTo(started.attempt().leaseOwner());
        assertThat(f.checks.findByTaskId(task.id())).hasSize(1);
        // Simulate process death between committed inner F02 completion and outer step publication.
        f.worker.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(60));
        assertThat(f.worker.coordinator.recover(f.worker.currentCandidate(), "technical-worker-restarted")).isEmpty();
        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(f.worker.attempt.get().outputResourceId()).isEqualTo(task.id());
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).hasSize(1);
        assertThat(f.worker.idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                started.command().idempotencyKey()).orElseThrow().status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThatThrownBy(() -> f.worker.coordinator.complete(started, computed)).isInstanceOf(RuntimeException.class);
        assertThat(f.worker.events).allSatisfy(event -> assertThat(event.userId()).isEqualTo(started.run().requestedBy()));
    }

    @Test void provenInnerF02WithUnpublishableNavigationClosesUnknownInsteadOfLoopingRunningForever() {
        var f = navigationV2Fixture();
        var started = f.worker.claim("technical-worker-a");
        var computed = f.executor.execute(runningInput(f.input, started.run()), new ReviewExecutionContext(started.run(), started.step(),
                started.attempt(), started.actor(), started.command().metadata(),
                started.f02Configurations(), value -> {}));
        var invalidMapper = mock(FrozenF02ResultMapper.class);
        when(invalidMapper.map(any(), any(), eq(computed.outputResourceId()), anyList()))
                .thenThrow(ReviewWorkerLeasePolicy.conflict());
        f.worker.coordinator = f.worker.coordinator(f.completes, f.outputs, invalidMapper);
        f.worker.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(60));

        assertThat(f.worker.coordinator.recover(
                f.worker.currentCandidate(), "technical-worker-restarted")).isEmpty();

        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.worker.step.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.worker.attempt.get().safeReasonCode())
                .isEqualTo("WORKER_F02_PUBLICATION_UNPROVEN");
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).hasSize(1);
    }

    @Test void innerF02StartWithoutProvenCompletionBecomesUnknownWithoutCreatingAnotherTask() {
        var f = fixture();
        var started = f.worker.claim("technical-worker-a");
        var key = FrozenF02ReviewStepExecutor.internalKey(started.run().runId(), started.step().stepId(), started.attempt().attemptId());
        f.worker.idempotency.claim(new IdempotencyRequest(ContractReviewService.IDEMPOTENCY_SCOPE, key,
                started.actor().userId(), "CONTRACT", started.run().contractId().toString(), "a".repeat(64),
                "AUDIT_TASK", UUID.randomUUID().toString(), "test-request", "test-trace", ReviewWorkerTransactionCoordinatorTest.NOW));
        f.worker.now.set(ReviewWorkerTransactionCoordinatorTest.NOW.plusSeconds(60));
        assertThat(f.worker.coordinator.recover(f.worker.currentCandidate(), "technical-worker-b")).isEmpty();
        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).isEmpty();
        assertThat(f.worker.idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, key)
                .orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    private static FrozenReviewInput runningInput(FrozenReviewInput frozen, ContractReviewRun claimed) {
        // Mirror the production worker: immutable material identity is retained, execution status
        // comes from the successfully claimed Run rather than the pre-claim PENDING fixture.
        return new FrozenReviewInput(claimed, frozen.frozenContract(), frozen.materials());
    }

    private Fixture fixture() {
        var w = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.F02_DETERMINISTIC);
        var contract = LocalContractFixtures.contracts().get(0);
        var r = w.run.get();
        w.run.set(new ContractReviewRun(r.runId(), r.projectId(), contract.id(), contract.versionNo(), r.sourceCandidateId(),
                r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(), r.contractSnapshotSha256(),
                r.reviewProfileVersion(), r.ruleVersion(), r.modelPolicyVersion(), r.inputSnapshotIdentity(), r.requestedBy(),
                r.status(), r.requestedAt(), r.startedAt(), r.completedAt(), r.failedAt(), r.unknownAt(), r.safeReasonCode(), r.versionNo()));
        var tasks = new InMemoryAuditTaskRepository();
        var checks = new InMemoryAuditCheckResultRepository();
        var findings = mock(ContractReviewFindingRepository.class);
        var evidence = mock(ContractReviewEvidenceRepository.class);
        var materials = mock(ContractReviewRunMaterialRepository.class);
        var clock = Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC);
        var completes = new ReviewStepCompleteTransactionCoordinator(w.runs, w.steps, w.attempts, materials, findings,
                evidence, w.idempotency, w.events::add, new ReviewRunStatusAggregator(), clock);
        var outputs = new ReviewStepOutputReadService(mock(ContractReviewRunService.class), findings, evidence, tasks, checks, w.idempotency);
        w.coordinator = w.coordinator(completes, outputs);
        var flow = new FlowDebugLogger(new ObservabilityProperties());
        var reviews = new ContractReviewService(id -> { throw new AssertionError("must use frozen contract"); },
                () -> { throw new AssertionError("must use frozen policy"); },
                new ContractReviewTransactionCoordinator(checks, tasks, w.idempotency, w.events::add, flow),
                Optional::empty, clock, flow, List.of(new AwardAmountMatchRule()));
        return new Fixture(w, new FrozenF02ReviewStepExecutor(reviews), new FrozenReviewInput(w.run.get(), contract, List.of()), tasks, checks);
    }

    private NavigationV2Fixture navigationV2Fixture() {
        var w = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.F02_DETERMINISTIC);
        var contract = LocalContractFixtures.contracts().get(0);
        var old = w.run.get();
        w.run.set(new ContractReviewRun(old.runId(), old.projectId(), contract.id(), contract.versionNo(),
                old.sourceCandidateId(), old.sourceConfirmationId(), old.runSchemaVersion(), old.contractSnapshotCanonicalJson(),
                old.contractSnapshotSha256(), old.reviewProfileVersion(), old.ruleVersion(), old.modelPolicyVersion(),
                old.inputSnapshotIdentity(), old.requestedBy(), old.status(), old.requestedAt(), old.startedAt(),
                old.completedAt(), old.failedAt(), old.unknownAt(), old.safeReasonCode(), old.versionNo()));
        var tasks = new InMemoryAuditTaskRepository();
        var checks = new InMemoryAuditCheckResultRepository();
        var materials = mock(ContractReviewRunMaterialRepository.class);
        var results = new InMemoryContractReviewResultsRepositories(w.attempts, materials, w.steps);
        var clock = Clock.fixed(ReviewWorkerTransactionCoordinatorTest.NOW, ZoneOffset.UTC);
        var completes = new ReviewStepCompleteTransactionCoordinator(w.runs, w.steps, w.attempts, materials,
                results, results, w.idempotency, w.events::add, new ReviewRunStatusAggregator(), clock);
        var outputs = new ReviewStepOutputReadService(mock(ContractReviewRunService.class), results, results,
                tasks, checks, w.idempotency);
        var source = mock(FindingSourceProjectionService.class);
        when(source.projectFrozen(any(), any())).thenReturn(FindingSourceProjection.unavailable("SOURCE_NOT_FOUND"));
        var mapper = new FrozenF02ResultMapper(source, new FrozenEvidenceGrounder(), clock);
        w.coordinator = w.coordinator(completes, outputs, mapper);
        var flow = new FlowDebugLogger(new ObservabilityProperties());
        var reviews = new ContractReviewService(id -> { throw new AssertionError("must use frozen contract"); },
                () -> { throw new AssertionError("must use frozen policy"); },
                new ContractReviewTransactionCoordinator(checks, tasks, w.idempotency, w.events::add, flow),
                Optional::empty, clock, flow, List.of(new AwardAmountMatchRule()));
        var delegate = new FrozenF02ReviewStepExecutor(reviews, mapper);
        return new NavigationV2Fixture(w, delegate,
                new FrozenReviewInput(w.run.get(), contract, List.of()), tasks, results, completes, outputs);
    }
    private record Fixture(ReviewWorkerTransactionCoordinatorTest.Fixture worker, FrozenF02ReviewStepExecutor executor,
            FrozenReviewInput input, InMemoryAuditTaskRepository tasks, InMemoryAuditCheckResultRepository checks) {}
    private record NavigationV2Fixture(ReviewWorkerTransactionCoordinatorTest.Fixture worker,
            FrozenF02ReviewStepExecutor executor, FrozenReviewInput input, InMemoryAuditTaskRepository tasks,
            InMemoryContractReviewResultsRepositories results,
            ReviewStepCompleteTransactionCoordinator completes, ReviewStepOutputReadService outputs) { }
}
