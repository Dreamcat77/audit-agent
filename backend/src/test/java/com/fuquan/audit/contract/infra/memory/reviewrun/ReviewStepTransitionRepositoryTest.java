package com.fuquan.audit.contract.infra.memory.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ReviewStepTransitionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void exactStatusAndVersionCasClaimsOneFirstAttemptAndRejectsStaleWriter() {
        var fixture = fixture();
        var nextAttempt = running(fixture.attempt());
        var claimed = fixture.store().transition(fixture.attempt(), nextAttempt);
        assertThat(claimed.status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(claimed.versionNo()).isEqualTo(1);
        assertThat(fixture.store().findAttemptById(claimed.attemptId())).contains(claimed);
        assertThatThrownBy(() -> fixture.store().transition(fixture.attempt(), nextAttempt))
                .isInstanceOf(BusinessException.class);

        var nextStep = running(fixture.step());
        assertThat(fixture.store().transition(fixture.step(), nextStep)).isEqualTo(nextStep);
        assertThat(fixture.store().findStepById(nextStep.stepId())).contains(nextStep);
        var nextRun = running(fixture.run());
        assertThat(fixture.store().transition(fixture.run(), nextRun)).isEqualTo(nextRun);
        assertThat(fixture.store().findById(nextRun.runId())).contains(nextRun);
        assertThatThrownBy(() -> fixture.store().transition(fixture.run(), nextRun))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void concurrentSameAttemptCasHasExactlyOneWinner() throws Exception {
        var fixture = fixture();
        var next = running(fixture.attempt());
        var barrier = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> claim(fixture.store(), fixture.attempt(), next, barrier));
            var second = pool.submit(() -> claim(fixture.store(), fixture.attempt(), next, barrier));
            barrier.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixture.store().findAttemptById(next.attemptId())).contains(next);
    }

    @Test
    void externalRequestMarkIsVersionedWithoutChangingRunningAttemptIdentity() {
        var fixture = fixture();
        var claimed = fixture.store().transition(fixture.attempt(), running(fixture.attempt()));
        var marked = new ContractReviewStepAttempt(claimed.attemptId(), claimed.runId(), claimed.stepId(),
                claimed.attemptNo(), claimed.operationIdentity(), ContractReviewStepStatus.RUNNING,
                null, null, claimed.claimedAt(), ReviewStepExternalCallState.REQUESTED,
                "d".repeat(64), null, null, null, claimed.startedAt(), null, null, null, null, 2);
        assertThat(fixture.store().transition(claimed, marked)).isEqualTo(marked);
        assertThatThrownBy(() -> fixture.store().transition(claimed, marked))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void transitionCannotSkipRunningOrRewriteFrozenPolicyIdentity() {
        var fixture = fixture();
        var bypass = new ContractReviewStepAttempt(fixture.attempt().attemptId(),
                fixture.attempt().runId(), fixture.attempt().stepId(), 1,
                fixture.attempt().operationIdentity(), ContractReviewStepStatus.COMPLETED,
                null, null, NOW, ReviewStepExternalCallState.COMPLETED,
                null, null, null, null, NOW, NOW, null, "REVIEW_FINDING_SET",
                fixture.attempt().attemptId(), 1);
        assertThatThrownBy(() -> fixture.store().transition(fixture.attempt(), bypass))
                .isInstanceOf(BusinessException.class);
        var changedPolicy = new ContractReviewStep(fixture.step().stepId(), fixture.step().runId(),
                fixture.step().stepType(), fixture.step().required(), ContractReviewStepStatus.RUNNING,
                "different-policy", 1, null, null, null, NOW, null, 1);
        assertThatThrownBy(() -> fixture.store().transition(fixture.step(), changedPolicy))
                .isInstanceOf(BusinessException.class);
        var changedSnapshot = new ContractReviewRun(fixture.run().runId(), fixture.run().projectId(),
                fixture.run().contractId(), 0, null, null, 1,
                fixture.run().contractSnapshotCanonicalJson(), fixture.run().contractSnapshotSha256(),
                "profile-v1", "different-rule", "model-v1", "a".repeat(64),
                fixture.run().requestedBy(), ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1);
        assertThatThrownBy(() -> fixture.store().transition(fixture.run(), changedSnapshot))
                .isInstanceOf(BusinessException.class);
    }

    private static int claim(InMemoryContractReviewRunRepositories store,
            ContractReviewStepAttempt expected, ContractReviewStepAttempt next, CountDownLatch barrier) {
        try {
            barrier.await();
            store.transition(expected, next);
            return 1;
        } catch (BusinessException stale) {
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Fixture fixture() {
        var store = new InMemoryContractReviewRunRepositories();
        UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
        String canonical = "{\"contractVersion\":0}";
        var run = new ContractReviewRun(runId, projectId, contractId, 0, null, null, 1,
                canonical, ContractSnapshotCanonicalizer.sha256(canonical), "profile-v1", "rule-v1",
                "model-v1", "a".repeat(64), UUID.randomUUID().toString(),
                ContractReviewRunStatus.PENDING, NOW, null, null, null, null, null, 0);
        store.insert(run);
        var manifest = new ReviewRunManifestFactory().create(runId, NOW);
        manifest.steps().forEach(store::insert);
        manifest.attempts().forEach(store::insert);
        var step = manifest.steps().stream().filter(value ->
                value.stepType() == ContractReviewStepType.SEMANTIC_AI).findFirst().orElseThrow();
        var attempt = manifest.attempts().stream().filter(value ->
                value.stepId().equals(step.stepId())).findFirst().orElseThrow();
        return new Fixture(store, run, step, attempt);
    }

    private static ContractReviewRun running(ContractReviewRun value) {
        return new ContractReviewRun(value.runId(), value.projectId(), value.contractId(),
                value.contractVersion(), value.sourceCandidateId(), value.sourceConfirmationId(),
                value.runSchemaVersion(), value.contractSnapshotCanonicalJson(), value.contractSnapshotSha256(),
                value.reviewProfileVersion(), value.ruleVersion(), value.modelPolicyVersion(),
                value.inputSnapshotIdentity(), value.requestedBy(), ContractReviewRunStatus.RUNNING,
                value.requestedAt(), NOW, null, null, null, null, 1);
    }

    private static ContractReviewStep running(ContractReviewStep value) {
        return new ContractReviewStep(value.stepId(), value.runId(), value.stepType(), value.required(),
                ContractReviewStepStatus.RUNNING, value.stepPolicyVersion(), value.currentAttemptNo(),
                null, null, null, NOW, null, 1);
    }

    private static ContractReviewStepAttempt running(ContractReviewStepAttempt value) {
        return new ContractReviewStepAttempt(value.attemptId(), value.runId(), value.stepId(),
                value.attemptNo(), value.operationIdentity(), ContractReviewStepStatus.RUNNING,
                null, null, NOW, ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
    }

    private record Fixture(InMemoryContractReviewRunRepositories store, ContractReviewRun run,
            ContractReviewStep step, ContractReviewStepAttempt attempt) { }
}
