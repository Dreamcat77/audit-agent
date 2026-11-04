package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateResult;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Dm8ContractReviewRunRepositoriesTest {
    @Test
    void mapsFourFrozenRowTypesThroughMapperWithoutInventingLegacyDecisionOrExecution() {
        var mapper = mock(ContractReviewRunMapper.class);
        var adapter = new Dm8ContractReviewRunRepositories(mapper);
        UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID(), parseId = UUID.randomUUID(), identityId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        String canonical = "{\"contractVersion\":0}";
        var run = new ContractReviewRun(runId, projectId, contractId, 0, UUID.randomUUID(),
                null, 1, canonical, ContractSnapshotCanonicalizer.sha256(canonical),
                "profile-v1", "rule-v1", "policy-v1", "a".repeat(64),
                UUID.randomUUID().toString(), ContractReviewRunStatus.PENDING, now,
                null, null, null, null, null, 0);
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, projectId,
                fileId, "b".repeat(64), parseId, 1, "c".repeat(64), DocumentRole.CONTRACT_BODY,
                null, identityId, null, run.requestedBy(), now);
        var manifest = new ReviewRunManifestFactory().create(runId, now);
        when(mapper.insertRun(any())).thenReturn(1);
        when(mapper.insertMaterial(any())).thenReturn(1);
        when(mapper.insertStep(any())).thenReturn(1);
        when(mapper.insertAttempt(any())).thenReturn(1);
        adapter.insert(run);
        adapter.insert(material);
        manifest.steps().forEach(adapter::insert);
        manifest.attempts().forEach(adapter::insert);
        ArgumentCaptor<ContractReviewRunPersistenceRow> runRow =
                ArgumentCaptor.forClass(ContractReviewRunPersistenceRow.class);
        ArgumentCaptor<ContractReviewRunMaterialPersistenceRow> materialRow =
                ArgumentCaptor.forClass(ContractReviewRunMaterialPersistenceRow.class);
        ArgumentCaptor<ContractReviewStepPersistenceRow> stepRows =
                ArgumentCaptor.forClass(ContractReviewStepPersistenceRow.class);
        ArgumentCaptor<ContractReviewStepAttemptPersistenceRow> attemptRows =
                ArgumentCaptor.forClass(ContractReviewStepAttemptPersistenceRow.class);
        verify(mapper).insertRun(runRow.capture());
        verify(mapper).insertMaterial(materialRow.capture());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(5)).insertStep(stepRows.capture());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(4)).insertAttempt(attemptRows.capture());
        assertThat(runRow.getValue().sourceConfirmationId()).isNull();
        assertThat(runRow.getValue().contractSnapshotCanonicalJson()).isEqualTo(canonical);
        assertThat(materialRow.getValue().roleDecisionId()).isNull();
        assertThat(attemptRows.getAllValues()).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.externalCallState()).isEqualTo("NOT_STARTED");
            assertThat(row.outputResourceId()).isNull();
        });
        when(mapper.findRunById(eq(runId.toString()))).thenReturn(runRow.getValue());
        when(mapper.findLatestByProjectIdAndContractIdAndRequestedBy(
                eq(projectId.toString()), eq(contractId.toString()), eq(run.requestedBy())))
                .thenReturn(runRow.getValue());
        when(mapper.findMaterialsByRunId(eq(runId.toString()))).thenReturn(List.of(materialRow.getValue()));
        var shuffledSteps = new ArrayList<>(stepRows.getAllValues());
        var shuffledAttempts = new ArrayList<>(attemptRows.getAllValues());
        Collections.reverse(shuffledSteps);
        Collections.reverse(shuffledAttempts);
        when(mapper.findStepsByRunId(eq(runId.toString()))).thenReturn(shuffledSteps);
        when(mapper.findAttemptsByRunId(eq(runId.toString()))).thenReturn(shuffledAttempts);
        assertThat(adapter.findById(runId)).contains(run);
        assertThat(adapter.findLatestByProjectIdAndContractIdAndRequestedBy(
                projectId, contractId, run.requestedBy())).contains(run);
        verify(mapper).findLatestByProjectIdAndContractIdAndRequestedBy(
                projectId.toString(), contractId.toString(), run.requestedBy());
        assertThat(adapter.findMaterialsByRunId(runId)).containsExactly(material);
        var memory = new InMemoryContractReviewRunRepositories();
        memory.insert(run);
        memory.insert(material);
        manifest.steps().forEach(memory::insert);
        manifest.attempts().forEach(memory::insert);
        var dm8Projection = new ContractReviewRunCreateResult(run, List.of(material),
                adapter.findStepsByRunId(runId), adapter.findAttemptsByRunId(runId), true);
        var memoryProjection = new ContractReviewRunCreateResult(run, List.of(material),
                memory.findStepsByRunId(runId), memory.findAttemptsByRunId(runId), true);
        assertThat(dm8Projection.steps()).containsExactlyElementsOf(memoryProjection.steps());
        assertThat(dm8Projection.attempts()).containsExactlyElementsOf(memoryProjection.attempts());
    }
}
