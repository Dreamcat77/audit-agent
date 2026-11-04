package com.fuquan.audit.contract.infra.memory.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InMemoryContractReviewRunRepositoriesTest {
    private final InMemoryContractReviewRunRepositories store = new InMemoryContractReviewRunRepositories();
    private final UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void insertsAndReopensExactlyOneAggregateWithFiveStepsAndFourAttempts() {
        var run = run();
        var material = material();
        var manifest = new ReviewRunManifestFactory().create(runId, now);
        store.insert(run);
        store.insert(material);
        manifest.steps().forEach(store::insert);
        manifest.attempts().forEach(store::insert);
        assertThat(store.findById(runId)).contains(run);
        assertThat(store.findMaterialsByRunId(runId)).containsExactly(material);
        assertThat(store.findStepsByRunId(runId)).containsExactlyElementsOf(manifest.steps());
        assertThat(store.findAttemptsByRunId(runId)).containsExactlyElementsOf(manifest.attempts());
        assertThatThrownBy(() -> store.insert(run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.insert(material)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rollbackRemovesOnlyThisUncommittedRunAndItsChildren() {
        var manifest = new ReviewRunManifestFactory().create(runId, now);
        store.insert(run()); store.insert(material());
        manifest.steps().forEach(store::insert); manifest.attempts().forEach(store::insert);
        store.rollbackUncommittedRun(runId);
        assertThat(store.findById(runId)).isEmpty();
        assertThat(store.findMaterialsByRunId(runId)).isEmpty();
        assertThat(store.findStepsByRunId(runId)).isEmpty();
        assertThat(store.findAttemptsByRunId(runId)).isEmpty();
    }

    @Test
    void findsLatestOwnedRunByRequestedAtThenRunIdWithoutFallingBackAcrossAuthorityFields() {
        String actor = "actor-a";
        Instant latest = now.plusSeconds(60);
        UUID lowerRunId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID expectedRunId = UUID.fromString("00000000-0000-0000-0000-00000000000f");
        store.insert(run(UUID.randomUUID(), projectId, contractId, actor,
                ContractReviewRunStatus.COMPLETED, now));
        store.insert(run(lowerRunId, projectId, contractId, actor,
                ContractReviewRunStatus.FAILED, latest));
        var expected = run(expectedRunId, projectId, contractId, actor,
                ContractReviewRunStatus.UNKNOWN, latest);
        store.insert(expected);
        store.insert(run(UUID.randomUUID(), UUID.randomUUID(), contractId, actor,
                ContractReviewRunStatus.RUNNING, latest.plusSeconds(60)));
        store.insert(run(UUID.randomUUID(), projectId, UUID.randomUUID(), actor,
                ContractReviewRunStatus.RUNNING, latest.plusSeconds(60)));
        store.insert(run(UUID.randomUUID(), projectId, contractId, "actor-b",
                ContractReviewRunStatus.RUNNING, latest.plusSeconds(60)));

        assertThat(store.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor))
                .contains(expected);
    }

    @ParameterizedTest
    @EnumSource(ContractReviewRunStatus.class)
    void latestOwnedLookupIncludesEveryRunStatus(ContractReviewRunStatus status) {
        var matching = run(UUID.randomUUID(), projectId, contractId, "actor-a", status, now);
        store.insert(matching);

        assertThat(store.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, "actor-a"))
                .contains(matching);
    }

    @Test
    void latestOwnedLookupReturnsEmptyWhenOnlyAnotherActorHasRows() {
        store.insert(run(UUID.randomUUID(), projectId, contractId, "actor-b",
                ContractReviewRunStatus.COMPLETED, now));

        assertThat(store.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, "actor-a"))
                .isEmpty();
    }

    private ContractReviewRun run() {
        return run(runId, projectId, contractId, UUID.randomUUID().toString(),
                ContractReviewRunStatus.PENDING, now);
    }

    private ContractReviewRun run(UUID selectedRunId, UUID selectedProjectId, UUID selectedContractId,
            String requestedBy, ContractReviewRunStatus status, Instant requestedAt) {
        return new ContractReviewRun(selectedRunId, selectedProjectId, selectedContractId, 0,
                UUID.randomUUID(), null, 1,
                "{}", "a".repeat(64), "review-profile-v1", "f02-rule-v1", "model-policy-v1",
                "b".repeat(64), requestedBy, status, requestedAt,
                null, null, null, null, null, 0);
    }

    private ContractReviewRunMaterial material() {
        return new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, projectId, UUID.randomUUID(),
                "c".repeat(64), UUID.randomUUID(), 1, "d".repeat(64), DocumentRole.CONTRACT_BODY,
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID().toString(), now);
    }
}
