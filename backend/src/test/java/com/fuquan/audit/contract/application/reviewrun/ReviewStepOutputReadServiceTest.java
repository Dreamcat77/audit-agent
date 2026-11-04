package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewEvidenceRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewFindingRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewClauseComparisonRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewStepOutputReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");
    private final UUID projectId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final ContractReviewRunService runs = mock(ContractReviewRunService.class);
    private final ContractReviewFindingRepository findings = mock(ContractReviewFindingRepository.class);
    private final ContractReviewEvidenceRepository evidence = mock(ContractReviewEvidenceRepository.class);
    private final ContractReviewClauseComparisonRepository typedComparisons =
            mock(ContractReviewClauseComparisonRepository.class);
    private final AuditTaskRepository auditTasks = mock(AuditTaskRepository.class);
    private final AuditCheckResultRepository checks = mock(AuditCheckResultRepository.class);
    private final IdempotencyPort idempotency = mock(IdempotencyPort.class);
    private final ReviewStepOutputReadService service = new ReviewStepOutputReadService(
            runs, findings, evidence, typedComparisons, auditTasks, checks, idempotency);
    private ContractReviewRun run;
    private ContractReviewRunMaterial material;
    private List<ContractReviewStep> steps;
    private List<ContractReviewStepAttempt> attempts;

    @BeforeEach
    void setUp() {
        run = run();
        material = material();
        var manifest = new ReviewRunManifestFactory().create(runId, NOW);
        steps = new ArrayList<>(manifest.steps());
        attempts = new ArrayList<>(manifest.attempts());
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of());
        when(evidence.findEvidenceByRunId(runId)).thenReturn(List.of());
        when(typedComparisons.findByRunId(runId)).thenReturn(List.of());
    }

    @Test
    void supplierV3ReopensAndPublishesAllWitnessesUsingOnlyItsFrozenManifest() {
        var old = step(ContractReviewStepType.F02_DETERMINISTIC);
        var attempt = attempt(old.stepId());
        String policy = "f02-policy-v2:" + "e".repeat(64);
        run = new ContractReviewRun(run.runId(), run.projectId(), run.contractId(), run.contractVersion(),
                run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(),
                run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                policy, run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(), run.status(),
                run.requestedAt(), run.startedAt(), run.completedAt(), run.failedAt(), run.unknownAt(), run.safeReasonCode(), run.versionNo());
        var versioned = new ContractReviewStep(old.stepId(), old.runId(), old.stepType(), old.required(),
                old.status(), policy, old.currentAttemptNo(), null, null, null, old.startedAt(), old.completedAt(), old.versionNo());
        UUID taskId = UUID.randomUUID();
        replace(completed(versioned, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId),
                completed(attempt, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId));
        var step = step(ContractReviewStepType.F02_DETERMINISTIC);
        var check = check(taskId, F02RuleCodes.AWARD_SUPPLIER_MATCH, null);
        var finding = new ContractReviewFinding(FrozenF02ResultMapper.findingId(check.id()), runId, step.stepId(),
                attempt.attemptId(), 1, FrozenF02ResultMapper.FINDING_TYPE, check.ruleCode(), check.checkStatus().name(),
                null, check.riskLevel().name(), check.detail(), null, 2, policy, ReviewFindingSource.DETERMINISTIC,
                null, null, null, null, null, NOW, 0);
        var award = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 2, projectId, UUID.randomUUID(),
                "d".repeat(64), UUID.randomUUID(), 1, "e".repeat(64), DocumentRole.AWARD_NOTICE,
                UUID.randomUUID(), UUID.randomUUID(), null, run.requestedBy(), NOW.minusSeconds(5));
        var proofs = List.of(supplierEvidence(check, finding, material, 1), supplierEvidence(check, finding, material, 2),
                supplierEvidence(check, finding, award, 3));
        when(runs.get(projectId, contractId, runId)).thenReturn(new ContractReviewRunCreateResult(
                run, List.of(material, award), steps, attempts, false));
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(completedF02Task(taskId, run.requestedBy())));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check));
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(finding));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(proofs);
        givenF02Operation(step, attempt, taskId, run.requestedBy());
        assertThat(service.getStep(projectId, contractId, runId, step.stepId()).evidence()).containsExactlyElementsOf(proofs);
        var result = new StepComputationResult(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId,
                List.of(finding), proofs, null, null, null);
        assertThat(service.validateF02Publication(run, step, attempt, result, run.requestedBy()).checks()).containsExactly(check);
        verify(findings, never()).insert(any());
        verify(evidence, never()).insert(any());
    }

    private ContractReviewEvidence supplierEvidence(AuditCheckResult check, ContractReviewFinding finding,
            ContractReviewRunMaterial m, int ordinal) {
        return supplierEvidence(check, finding, m, ordinal, "f02-frozen-evidence-v3");
    }

    private ContractReviewEvidence supplierEvidence(AuditCheckResult check, ContractReviewFinding finding,
            ContractReviewRunMaterial m, int ordinal, String validationPolicy) {
        return new ContractReviewEvidence(FrozenF02ResultMapper.evidenceId(check.id(), m.fileId(), m.parseResultId(), ordinal),
                finding.findingId(), runId, finding.stepId(), finding.attemptId(), ordinal, m.fileId(), m.fileSha256(),
                m.parseResultId(), m.parseVersion(), m.canonicalTextSha256(), "COMPARISON", "VALIDATED",
                validationPolicy, null, null, null, null, null, null, true,
                ordinal * 10, ordinal * 10 + 5, "f".repeat(64), "proof", 1, NOW, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"f02-frozen-evidence-v1", "f02-frozen-evidence-v2"})
    void oldCompletedSupplierEvidenceBytesReopenUnchangedAndRetainExactTwoGate(String policy) throws Exception {
        var original = step(ContractReviewStepType.F02_DETERMINISTIC);
        var attempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId),
                completed(attempt, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId));
        var check = check(taskId, F02RuleCodes.AWARD_SUPPLIER_MATCH, null);
        var finding = new ContractReviewFinding(FrozenF02ResultMapper.findingId(check.id()), runId, original.stepId(),
                attempt.attemptId(), 1, FrozenF02ResultMapper.FINDING_TYPE, check.ruleCode(), check.checkStatus().name(),
                null, check.riskLevel().name(), check.detail(), null, 2, original.stepPolicyVersion(),
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null, NOW, 0);
        var award = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 2, projectId, UUID.randomUUID(),
                "d".repeat(64), UUID.randomUUID(), 1, "e".repeat(64), DocumentRole.AWARD_NOTICE,
                UUID.randomUUID(), UUID.randomUUID(), null, run.requestedBy(), NOW.minusSeconds(5));
        var proofs = List.of(supplierEvidence(check, finding, material, 1, policy), supplierEvidence(check, finding, award, 2, policy));
        when(runs.get(projectId, contractId, runId)).thenReturn(new ContractReviewRunCreateResult(
                run, List.of(material, award), steps, attempts, false));
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(completedF02Task(taskId, run.requestedBy())));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check));
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(finding));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(proofs);
        givenF02Operation(original, attempt, taskId, run.requestedBy());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        byte[] before = json.writeValueAsBytes(service.getStep(projectId, contractId, runId, original.stepId()));
        byte[] after = json.writeValueAsBytes(service.getStep(projectId, contractId, runId, original.stepId()));
        assertThat(after).isEqualTo(before);
        assertThat(service.getStep(projectId, contractId, runId, original.stepId()).evidence()).containsExactlyElementsOf(proofs);
        var extra = new ArrayList<>(proofs);
        extra.add(supplierEvidence(check, finding, award, 3, policy));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(extra);
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
        verify(findings, never()).insert(any());
        verify(evidence, never()).insert(any());
    }

    @Test
    void reopensCompletedF02OnlyFromRealOwnedAuditTaskAndCheckRows() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask task = new AuditTask(taskId, "CONTRACT", contractId, run.contractVersion(),
                "F02_DETERMINISTIC", AuditTaskStatus.DONE, run.requestedBy(), "request-1", "trace-1",
                NOW.minusSeconds(3), NOW.minusSeconds(2), NOW.minusSeconds(1), 2);
        AuditCheckResult second = check(taskId, "F02-02", "line-2");
        AuditCheckResult first = check(taskId, "F02-01", null);
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(second, first));
        givenF02Operation(original, originalAttempt, taskId, run.requestedBy());

        var output = service.getStep(projectId, contractId, runId, original.stepId());

        assertThat(output.run()).isEqualTo(run);
        assertThat(output.step().outputResourceId()).isEqualTo(taskId);
        assertThat(output.attempts()).extracting(ContractReviewStepAttempt::attemptId)
                .containsExactly(originalAttempt.attemptId());
        assertThat(output.auditTask()).isEqualTo(task);
        assertThat(output.auditCheckResults()).extracting(AuditCheckResult::ruleCode)
                .containsExactly("F02-01", "F02-02");
        assertThat(output.findings()).isEmpty();
        assertThat(output.evidence()).isEmpty();
        verify(idempotency, never()).claim(any());
        verify(idempotency, never()).markSucceeded(any(), any(), any(), any());
        verify(idempotency, never()).markFailed(any(), any());
        verify(idempotency, never()).rollbackUncommittedClaim(any());
        verify(auditTasks, never()).create(any());
        verify(checks, never()).appendAll(any());
    }

    @Test
    void navigationV2ReopensStableF02CarriersAndRejectsSilentEmptyDowngrade() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId),
                completed(originalAttempt, FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, taskId));
        ContractReviewStep completedStep = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt completedAttempt = attempt(completedStep.stepId());
        AuditTask task = completedF02Task(taskId, run.requestedBy());
        AuditCheckResult check = check(taskId, F02RuleCodes.AWARD_AMOUNT_MATCH, null);
        ContractReviewFinding carrier = new ContractReviewFinding(
                FrozenF02ResultMapper.findingId(check.id()), runId, completedStep.stepId(),
                completedAttempt.attemptId(), 1, FrozenF02ResultMapper.FINDING_TYPE,
                check.ruleCode(), check.checkStatus().name(), "SOURCE_UNAVAILABLE:SOURCE_NOT_FOUND",
                check.riskLevel().name(), check.detail(), null,
                FrozenF02ResultMapper.FINDING_SCHEMA_VERSION, completedStep.stepPolicyVersion(),
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null, NOW, 0);
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check));
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(carrier));
        givenF02Operation(original, originalAttempt, taskId, run.requestedBy());
        givenManifest();

        var output = service.getStep(projectId, contractId, runId, completedStep.stepId());

        assertThat(output.auditCheckResults()).containsExactly(check);
        assertThat(output.findings()).containsExactly(carrier);
        assertThat(output.evidence()).isEmpty();

        when(findings.findFindingsByRunId(runId)).thenReturn(List.of());
        assertConflict(() -> service.getStep(projectId, contractId, runId, completedStep.stepId()));
    }

    @Test
    void rejectsCompletedF02OutputFromAnotherValidTaskForTheSameContractVersion() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID boundTaskId = UUID.randomUUID();
        UUID substitutedTaskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", substitutedTaskId),
                completed(originalAttempt, "AUDIT_TASK", substitutedTaskId));
        givenManifest();
        AuditTask boundTask = completedF02Task(boundTaskId, "step-actor");
        AuditTask substitutedTask = completedF02Task(substitutedTaskId, "step-actor");
        when(auditTasks.findById(boundTaskId)).thenReturn(java.util.Optional.of(boundTask));
        when(auditTasks.findById(substitutedTaskId)).thenReturn(java.util.Optional.of(substitutedTask));
        when(checks.findByTaskId(boundTaskId)).thenReturn(List.of(check(boundTaskId, "F02-01", null)));
        when(checks.findByTaskId(substitutedTaskId))
                .thenReturn(List.of(check(substitutedTaskId, "F02-01", null)));
        String innerKey = FrozenF02ReviewStepExecutor.internalKey(
                runId, original.stepId(), originalAttempt.attemptId());
        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(new IdempotencyRecord(
                        UUID.randomUUID(), ContractReviewService.IDEMPOTENCY_SCOPE, innerKey,
                        "step-actor", "CONTRACT", contractId.toString(), "f".repeat(64),
                        IdempotencyStatus.SUCCEEDED, "AUDIT_TASK", boundTaskId.toString(),
                        "request-1", "trace-1", NOW.minusSeconds(3), NOW.minusSeconds(1), 1)));

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void rejectsCompletedF02WhenExactInnerOperationIsMissingOrNotSucceeded() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask task = completedF02Task(taskId, "step-actor");
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check(taskId, "F02-01", null)));
        String innerKey = innerKey(original, originalAttempt);

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.empty());
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
        verify(auditTasks, never()).findById(any());

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, taskId, "step-actor", IdempotencyStatus.IN_PROGRESS,
                        "CONTRACT", contractId.toString(), "AUDIT_TASK")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, taskId, "step-actor", IdempotencyStatus.FAILED,
                        "CONTRACT", contractId.toString(), "AUDIT_TASK")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void rejectsCompletedF02WhenInnerOperationResourceOrResultBindingDiffers() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask task = completedF02Task(taskId, "step-actor");
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check(taskId, "F02-01", null)));
        String innerKey = innerKey(original, originalAttempt);

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, taskId, "step-actor", IdempotencyStatus.SUCCEEDED,
                        "CONTRACT", UUID.randomUUID().toString(), "AUDIT_TASK")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, taskId, "step-actor", IdempotencyStatus.SUCCEEDED,
                        "PROJECT", contractId.toString(), "AUDIT_TASK")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, taskId, "step-actor", IdempotencyStatus.SUCCEEDED,
                        "CONTRACT", contractId.toString(), "REVIEW_FINDING_SET")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, innerKey))
                .thenReturn(java.util.Optional.of(f02Operation(
                        original, originalAttempt, UUID.randomUUID(), "step-actor",
                        IdempotencyStatus.SUCCEEDED, "CONTRACT", contractId.toString(), "AUDIT_TASK")));
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void rejectsCompletedF02WhenInnerOperationActorDiffersFromAuditTaskCreator() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask task = completedF02Task(taskId, "step-actor");
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check(taskId, "F02-01", null)));
        givenF02Operation(original, originalAttempt, taskId, "different-actor");

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void rejectsF02TaskOrCheckRowsThatDoNotBelongToFrozenContractAndTask() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask foreign = new AuditTask(taskId, "CONTRACT", UUID.randomUUID(), run.contractVersion(),
                "F02_DETERMINISTIC", AuditTaskStatus.DONE, run.requestedBy(), "request-1", "trace-1",
                NOW.minusSeconds(3), NOW.minusSeconds(2), NOW.minusSeconds(1), 2);
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(foreign));
        givenF02Operation(original, originalAttempt, taskId, run.requestedBy());

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        AuditTask owned = new AuditTask(taskId, "CONTRACT", contractId, run.contractVersion(),
                "F02_DETERMINISTIC", AuditTaskStatus.DONE, run.requestedBy(), "request-1", "trace-1",
                NOW.minusSeconds(3), NOW.minusSeconds(2), NOW.minusSeconds(1), 2);
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(owned));
        when(checks.findByTaskId(taskId)).thenReturn(List.of(check(UUID.randomUUID(), "F02-01", null)));

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void completedF02WithoutDurableCheckRowsIsCorruptNotAnEmptySuccess() {
        ContractReviewStep original = step(ContractReviewStepType.F02_DETERMINISTIC);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        UUID taskId = UUID.randomUUID();
        replace(completed(original, "AUDIT_TASK", taskId),
                completed(originalAttempt, "AUDIT_TASK", taskId));
        givenManifest();
        AuditTask task = new AuditTask(taskId, "CONTRACT", contractId, run.contractVersion(),
                "F02_DETERMINISTIC", AuditTaskStatus.DONE, run.requestedBy(), "request-1", "trace-1",
                NOW.minusSeconds(3), NOW.minusSeconds(2), NOW.minusSeconds(1), 2);
        when(auditTasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(checks.findByTaskId(taskId)).thenReturn(List.of());
        givenF02Operation(original, originalAttempt, taskId, run.requestedBy());

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void reopensNonF02FindingsAndEvidenceInStableLogicalOrder() {
        ContractReviewStep original = step(ContractReviewStepType.BREACH);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        replace(completed(original, "REVIEW_FINDING_SET", originalAttempt.attemptId()),
                completed(originalAttempt, "REVIEW_FINDING_SET", originalAttempt.attemptId()));
        givenManifestReversed();
        ContractReviewFinding second = finding(original, originalAttempt, 2);
        ContractReviewFinding first = finding(original, originalAttempt, 1);
        ContractReviewEvidence secondEvidence = evidence(second, original, originalAttempt, 2);
        ContractReviewEvidence firstEvidence = evidence(first, original, originalAttempt, 1);
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(second, first));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(List.of(secondEvidence, firstEvidence));

        var output = service.getStep(projectId, contractId, runId, original.stepId());

        assertThat(output.findings()).extracting(ContractReviewFinding::ordinal).containsExactly(1, 2);
        assertThat(output.evidence()).extracting(ContractReviewEvidence::findingId)
                .containsExactly(first.findingId(), second.findingId());
        assertThat(output.evidence()).extracting(ContractReviewEvidence::ordinal).containsExactly(1, 2);
        assertThat(output.auditTask()).isNull();
        assertThat(output.auditCheckResults()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void completedHistoricalV1ClauseOutputsReopenWithoutPolicyMigrationOrWrites(ContractReviewStepType type) {
        var original = step(type);
        var originalAttempt = attempt(original.stepId());
        String oldPolicy = type == ContractReviewStepType.BREACH ? "breach-analyzer-v1" : "term-analyzer-v1";
        var oldStep = new ContractReviewStep(original.stepId(), original.runId(), original.stepType(), original.required(),
                original.status(), oldPolicy, original.currentAttemptNo(), original.outputResourceType(),
                original.outputResourceId(), original.safeReasonCode(), original.startedAt(), original.completedAt(), original.versionNo());
        replace(completed(oldStep, "REVIEW_FINDING_SET", originalAttempt.attemptId()),
                completed(originalAttempt, "REVIEW_FINDING_SET", originalAttempt.attemptId()));
        var persisted = finding(oldStep, originalAttempt, 1);
        var persistedEvidence = evidence(persisted, oldStep, originalAttempt, 1);
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(persisted));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(List.of(persistedEvidence));
        givenManifest();
        var first = service.getStep(projectId, contractId, runId, oldStep.stepId());
        var second = service.getStep(projectId, contractId, runId, oldStep.stepId());
        assertThat(first).isEqualTo(second);
        assertThat(first.step().stepPolicyVersion()).isEqualTo(oldPolicy);
        assertThat(first.findings()).containsExactly(persisted);
        assertThat(first.evidence()).containsExactly(persistedEvidence);
        assertThat(first.findings().get(0).policyVersion()).isEqualTo(oldPolicy);
        verify(findings, never()).insert(any());
        verify(evidence, never()).insert(any());
        verify(idempotency, never()).claim(any());
    }

    @Test
    void reopensTypedClauseV2OnlyWhenBothSidesAreBoundToThePersistedFindingEvidence() {
        ContractReviewStep original = step(ContractReviewStepType.BREACH);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        replace(completed(original, "REVIEW_FINDING_SET", originalAttempt.attemptId()),
                completed(originalAttempt, "REVIEW_FINDING_SET", originalAttempt.attemptId()));
        givenManifest();
        ContractReviewFinding finding = new ContractReviewFinding(UUID.randomUUID(), runId,
                original.stepId(), originalAttempt.attemptId(), 1, "BREACH_COMPARISON",
                "OVERDUE_PAYMENT", "DIFFERENT", null, "YELLOW", "typed comparison", null, 2,
                original.stepPolicyVersion(), ReviewFindingSource.MODEL_ASSISTED,
                "private-provider", "model", "revision", "prompt-v2", "schema-v2", NOW, 0);
        ContractReviewEvidence leftEvidence = evidence(finding, original, originalAttempt, 1);
        ContractReviewEvidence rightEvidence = evidence(finding, original, originalAttempt, 2);
        UUID comparisonId = UUID.randomUUID();
        ContractReviewClauseComparison comparison = new ContractReviewClauseComparison(
                comparisonId, finding.findingId(), runId, original.stepId(),
                originalAttempt.attemptId(), "overdue-payment", "BREACH", "DIFFERENT", 2,
                List.of(
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, runId,
                                "CONTRACT", material.fileId(), "KNOWN", "overdue-payment",
                                "PARTY_B", "OVERDUE_AMOUNT", new BigDecimal("0.01"), "PERCENT",
                                "PER_DAY", null, null, null, null, null, null, null, null, null,
                                null, List.of(leftEvidence.evidenceId())),
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, runId,
                                "TENDER", material.fileId(), "KNOWN", "overdue-payment",
                                "PARTY_B", "OVERDUE_AMOUNT", new BigDecimal("0.02"), "PERCENT",
                                "PER_DAY", null, null, null, null, null, null, null, null, null,
                                null, List.of(rightEvidence.evidenceId()))));
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(finding));
        when(evidence.findEvidenceByRunId(runId)).thenReturn(List.of(leftEvidence, rightEvidence));
        when(typedComparisons.findByRunId(runId)).thenReturn(List.of(comparison));

        var output = service.getStep(projectId, contractId, runId, original.stepId());

        assertThat(output.typedComparisons()).containsExactly(comparison);
        verify(typedComparisons, never()).insert(any());

        when(typedComparisons.findByRunId(runId)).thenReturn(List.of());
        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void rejectsCorruptFindingOrEvidenceOwnershipInsteadOfBlindProjection() {
        ContractReviewStep original = step(ContractReviewStepType.BREACH);
        ContractReviewStepAttempt originalAttempt = attempt(original.stepId());
        replace(completed(original, "REVIEW_FINDING_SET", originalAttempt.attemptId()),
                completed(originalAttempt, "REVIEW_FINDING_SET", originalAttempt.attemptId()));
        givenManifest();
        ContractReviewFinding valid = finding(original, originalAttempt, 1);
        ContractReviewFinding corrupt = new ContractReviewFinding(valid.findingId(), runId,
                step(ContractReviewStepType.SEMANTIC_AI).stepId(), originalAttempt.attemptId(), valid.ordinal(),
                valid.findingType(), valid.category(), valid.statusCode(), valid.attentionCode(),
                valid.riskLevel(), valid.summary(), valid.recommendation(), valid.findingSchemaVersion(),
                valid.policyVersion(), valid.source(), valid.modelProvider(), valid.modelName(),
                valid.modelRevision(), valid.promptVersion(), valid.modelSchemaVersion(),
                valid.createdAt(), valid.versionNo());
        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(corrupt));

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));

        when(findings.findFindingsByRunId(runId)).thenReturn(List.of(valid));
        ContractReviewEvidence validEvidence = evidence(valid, original, originalAttempt, 1);
        ContractReviewEvidence corruptEvidence = new ContractReviewEvidence(validEvidence.evidenceId(),
                validEvidence.findingId(), validEvidence.runId(), validEvidence.stepId(),
                validEvidence.attemptId(), validEvidence.ordinal(), UUID.randomUUID(),
                validEvidence.fileSha256(), validEvidence.parseResultId(), validEvidence.parseVersion(),
                validEvidence.canonicalTextSha256(), validEvidence.evidenceRole(),
                validEvidence.validationStatus(), validEvidence.validationPolicyVersion(), null, null,
                null, null, null, null, validEvidence.locatorAvailable(), validEvidence.startOffset(),
                validEvidence.endOffset(), validEvidence.excerptSha256(), validEvidence.boundedExcerpt(),
                validEvidence.locatorSchemaVersion(), validEvidence.createdAt(), validEvidence.versionNo());
        when(evidence.findEvidenceByRunId(runId)).thenReturn(List.of(corruptEvidence));

        assertConflict(() -> service.getStep(projectId, contractId, runId, original.stepId()));
    }

    @Test
    void runProjectionUsesStepEnumOrderAndParentAttemptOrderWithoutWrites() {
        givenManifestReversed();

        var output = service.getRun(projectId, contractId, runId);

        assertThat(output.steps()).extracting(value -> value.step().stepType()).containsExactly(
                ContractReviewStepType.MATERIAL_VALIDATION, ContractReviewStepType.F02_DETERMINISTIC,
                ContractReviewStepType.SEMANTIC_AI, ContractReviewStepType.BREACH,
                ContractReviewStepType.TERM);
        assertThat(output.steps().stream().flatMap(value -> value.attempts().stream())
                .map(ContractReviewStepAttempt::stepId).toList()).containsExactly(
                step(ContractReviewStepType.F02_DETERMINISTIC).stepId(),
                step(ContractReviewStepType.SEMANTIC_AI).stepId(),
                step(ContractReviewStepType.BREACH).stepId(), step(ContractReviewStepType.TERM).stepId());
        verify(findings, never()).insert(any());
        verify(evidence, never()).insert(any());
        verify(auditTasks, never()).create(any());
        verify(checks, never()).appendAll(any());
    }

    @Test
    void wrongRunOwnershipOrUnknownStepIsSafeNotFound() {
        when(runs.get(projectId, contractId, runId)).thenReturn(new ContractReviewRunCreateResult(
                run, List.of(material), steps, attempts, false));

        assertNotFound(() -> service.getStep(UUID.randomUUID(), contractId, runId,
                step(ContractReviewStepType.BREACH).stepId()));
        assertNotFound(() -> service.getStep(projectId, UUID.randomUUID(), runId,
                step(ContractReviewStepType.BREACH).stepId()));
        assertNotFound(() -> service.getStep(projectId, contractId, runId, UUID.randomUUID()));
    }

    private void givenManifest() {
        when(runs.get(projectId, contractId, runId)).thenReturn(new ContractReviewRunCreateResult(
                run, List.of(material), steps, attempts, false));
    }

    private void givenManifestReversed() {
        var reversedSteps = new ArrayList<>(steps);
        var reversedAttempts = new ArrayList<>(attempts);
        Collections.reverse(reversedSteps);
        Collections.reverse(reversedAttempts);
        when(runs.get(projectId, contractId, runId)).thenReturn(new ContractReviewRunCreateResult(
                run, List.of(material), reversedSteps, reversedAttempts, false));
    }

    private ContractReviewRun run() {
        return new ContractReviewRun(runId, projectId, contractId, 7, null, null, 1, "{}",
                ContractSnapshotCanonicalizer.sha256("{}"), "profile-v1", "rule-v1", "model-v1",
                "a".repeat(64), UUID.randomUUID().toString(), ContractReviewRunStatus.RUNNING,
                NOW.minusSeconds(5), NOW.minusSeconds(4), null, null, null, null, 1);
    }

    private ContractReviewRunMaterial material() {
        return new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, projectId, UUID.randomUUID(),
                "b".repeat(64), UUID.randomUUID(), 3, "c".repeat(64), DocumentRole.CONTRACT_BODY,
                UUID.randomUUID(), UUID.randomUUID(), null, run.requestedBy(), NOW.minusSeconds(5));
    }

    private ContractReviewStep step(ContractReviewStepType type) {
        return steps.stream().filter(value -> value.stepType() == type).findFirst().orElseThrow();
    }

    private ContractReviewStepAttempt attempt(UUID stepId) {
        return attempts.stream().filter(value -> value.stepId().equals(stepId)).findFirst().orElseThrow();
    }

    private void replace(ContractReviewStep replacement, ContractReviewStepAttempt attemptReplacement) {
        steps.removeIf(value -> value.stepId().equals(replacement.stepId()));
        attempts.removeIf(value -> value.attemptId().equals(attemptReplacement.attemptId()));
        steps.add(replacement);
        attempts.add(attemptReplacement);
    }

    private static ContractReviewStep completed(ContractReviewStep original, String type, UUID id) {
        return new ContractReviewStep(original.stepId(), original.runId(), original.stepType(), original.required(),
                ContractReviewStepStatus.COMPLETED, original.stepPolicyVersion(), original.currentAttemptNo(),
                type, id, null, NOW.minusSeconds(2), NOW.minusSeconds(1), original.versionNo() + 2);
    }

    private static ContractReviewStepAttempt completed(ContractReviewStepAttempt original, String type, UUID id) {
        return new ContractReviewStepAttempt(original.attemptId(), original.runId(), original.stepId(),
                original.attemptNo(), original.operationIdentity(), ContractReviewStepStatus.COMPLETED,
                null, null, NOW.minusSeconds(3), ReviewStepExternalCallState.NOT_STARTED, null,
                null, null, null, NOW.minusSeconds(2), NOW.minusSeconds(1), null, type, id,
                original.versionNo() + 2);
    }

    private ContractReviewFinding finding(ContractReviewStep step, ContractReviewStepAttempt attempt, int ordinal) {
        return new ContractReviewFinding(UUID.randomUUID(), runId, step.stepId(), attempt.attemptId(), ordinal,
                "BREACH", "CLAUSE", "NEEDS_REVIEW", null, null, "finding-" + ordinal, null, 1,
                step.stepPolicyVersion(), ReviewFindingSource.DETERMINISTIC, null, null, null, null, null,
                NOW, 0);
    }

    private ContractReviewEvidence evidence(ContractReviewFinding finding, ContractReviewStep step,
            ContractReviewStepAttempt attempt, int ordinal) {
        return new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(), runId, step.stepId(),
                attempt.attemptId(), ordinal, material.fileId(), material.fileSha256(),
                material.parseResultId(), material.parseVersion(), material.canonicalTextSha256(),
                finding.findingType().endsWith("_COMPARISON") ? "COMPARISON" : "PROBLEM",
                "VALIDATED", "ground-v1", null, null, null, null, null, null,
                true, ordinal - 1, ordinal, "d".repeat(64), "x", 1, NOW, 0);
    }

    private static AuditCheckResult check(UUID taskId, String ruleCode, String itemKey) {
        return new AuditCheckResult(UUID.randomUUID(), taskId, ruleCode, itemKey, CheckStatus.PASS,
                RiskLevel.GREEN, false, null, null, "ok", 1, NOW);
    }

    private AuditTask completedF02Task(UUID taskId, String createdBy) {
        return new AuditTask(taskId, "CONTRACT", contractId, run.contractVersion(),
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.DONE, createdBy,
                "request-1", "trace-1", NOW.minusSeconds(3), NOW.minusSeconds(2),
                NOW.minusSeconds(1), 2);
    }

    private void givenF02Operation(ContractReviewStep step, ContractReviewStepAttempt attempt,
            UUID taskId, String actorId) {
        String key = innerKey(step, attempt);
        when(idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE, key))
                .thenReturn(java.util.Optional.of(f02Operation(
                        step, attempt, taskId, actorId, IdempotencyStatus.SUCCEEDED,
                        "CONTRACT", contractId.toString(), "AUDIT_TASK")));
    }

    private IdempotencyRecord f02Operation(ContractReviewStep step, ContractReviewStepAttempt attempt,
            UUID taskId, String actorId, IdempotencyStatus status, String resourceType,
            String resourceId, String resultResourceType) {
        String key = innerKey(step, attempt);
        return new IdempotencyRecord(UUID.randomUUID(), ContractReviewService.IDEMPOTENCY_SCOPE,
                key, actorId, resourceType, resourceId, "f".repeat(64), status,
                resultResourceType, taskId.toString(), "request-1", "trace-1",
                NOW.minusSeconds(3), status == IdempotencyStatus.IN_PROGRESS
                        ? null : NOW.minusSeconds(1), 1);
    }

    private String innerKey(ContractReviewStep step, ContractReviewStepAttempt attempt) {
        return FrozenF02ReviewStepExecutor.internalKey(runId, step.stepId(), attempt.attemptId());
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.NOT_FOUND));
    }
}
