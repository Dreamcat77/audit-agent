package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.application.AuthContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContractReviewRunProjectionOrderingTest {
    private final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID(), runId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-16T00:00:00Z");
    private final ContractReviewRunMaterial material = new ContractReviewRunMaterial(UUID.randomUUID(), runId,
            1, projectId, UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), 1,
            "b".repeat(64), DocumentRole.CONTRACT_BODY, UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID().toString(), now);
    private final ContractReviewRun run = frozenRun();
    private final ReviewRunManifestFactory.Manifest manifest = new ReviewRunManifestFactory().create(runId, now);

    @Test
    void createProjectionOrdersShuffledStepsAndAttemptsByLogicalTypeThenAttemptNumber() {
        var result = new ContractReviewRunCreateResult(run, List.of(material), shuffled(manifest.steps()),
                shuffled(withSecondF02Attempt()), false);
        assertLogicalOrder(result);
    }

    @Test
    void getProjectionOrdersShuffledRepositoryRowsWithoutFollowingCurrentAuthority() {
        ContractReviewRunRepository runs = mock(ContractReviewRunRepository.class);
        ContractReviewRunMaterialRepository materials = mock(ContractReviewRunMaterialRepository.class);
        ContractReviewStepRepository steps = mock(ContractReviewStepRepository.class);
        ContractReviewStepAttemptRepository attempts = mock(ContractReviewStepAttemptRepository.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(materials.findMaterialsByRunId(runId)).thenReturn(List.of(material));
        when(steps.findStepsByRunId(runId)).thenReturn(shuffled(manifest.steps()));
        when(attempts.findAttemptsByRunId(runId)).thenReturn(shuffled(withSecondF02Attempt()));
        var service = new ContractReviewRunService(mock(AuthContext.class),
                mock(ContractReviewRunCreateTransactionCoordinator.class), runs, materials, steps,
                attempts, new ReviewRunSnapshotIdentityFactory());
        assertLogicalOrder(service.get(projectId, contractId, runId));
    }

    private ContractReviewRun frozenRun() {
        String canonicalJson = "{}";
        String sha = ContractSnapshotCanonicalizer.sha256(canonicalJson);
        String input = new ReviewRunSnapshotIdentityFactory().identity(1, projectId, contractId, 0,
                sha, null, null, List.of(material), "profile-v1", "rule-v1", "policy-v1");
        return new ContractReviewRun(runId, projectId, contractId, 0, null, null, 1,
                canonicalJson, sha, "profile-v1", "rule-v1", "policy-v1", input,
                material.selectedBy(), ContractReviewRunStatus.PENDING, now,
                null, null, null, null, null, 0);
    }

    private List<ContractReviewStepAttempt> withSecondF02Attempt() {
        var first = manifest.attempts().get(0);
        var second = new ContractReviewStepAttempt(UUID.randomUUID(), runId, first.stepId(), 2,
                ContractSnapshotCanonicalizer.sha256("second-f02-attempt:" + runId),
                ContractReviewStepStatus.PENDING, null, null, null,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                null, null, null, null, null, 0);
        var rows = new ArrayList<>(manifest.attempts());
        rows.add(second);
        return rows;
    }

    private static <T> List<T> shuffled(List<T> source) {
        var rows = new ArrayList<>(source);
        Collections.reverse(rows);
        return rows;
    }

    private static void assertLogicalOrder(ContractReviewRunCreateResult result) {
        assertThat(result.steps().stream().map(ContractReviewStep::stepType).toList()).containsExactly(
                ContractReviewStepType.MATERIAL_VALIDATION, ContractReviewStepType.F02_DETERMINISTIC,
                ContractReviewStepType.SEMANTIC_AI, ContractReviewStepType.BREACH,
                ContractReviewStepType.TERM);
        Map<UUID, ContractReviewStepType> parentTypes = result.steps().stream().collect(Collectors.toMap(
                ContractReviewStep::stepId, ContractReviewStep::stepType));
        assertThat(result.attempts().stream().map(value -> parentTypes.get(value.stepId())).toList())
                .containsExactly(ContractReviewStepType.F02_DETERMINISTIC,
                        ContractReviewStepType.F02_DETERMINISTIC, ContractReviewStepType.SEMANTIC_AI,
                        ContractReviewStepType.BREACH, ContractReviewStepType.TERM);
        assertThat(result.attempts().stream().map(ContractReviewStepAttempt::attemptNo).toList())
                .containsExactly(1, 2, 1, 1, 1);
    }
}
