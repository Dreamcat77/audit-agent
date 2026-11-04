package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewEvidenceRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewFindingRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewClauseComparisonRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewStepCompleteTransactionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReviewStepCompleteTransactionCoordinator.class);
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final ContractReviewRunMaterialRepository materials;
    private final ContractReviewFindingRepository findings;
    private final ContractReviewEvidenceRepository evidence;
    private final ContractReviewClauseComparisonRepository typedComparisons;
    private final ContractModelCallAuditRepository modelCalls;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final ReviewRunStatusAggregator aggregator;
    private final Clock clock;

    @Autowired
    public ReviewStepCompleteTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ContractReviewRunMaterialRepository materials, ContractReviewFindingRepository findings,
            ContractReviewEvidenceRepository evidence,
            ContractReviewClauseComparisonRepository typedComparisons, IdempotencyPort idempotency,
            ContractModelCallAuditRepository modelCalls,
            OperationAuditPort audit, ReviewRunStatusAggregator aggregator, Clock clock) {
        this.runs = runs; this.steps = steps; this.attempts = attempts; this.materials = materials;
        this.findings = findings; this.evidence = evidence; this.typedComparisons = typedComparisons;
        this.modelCalls = modelCalls;
        this.idempotency = idempotency;
        this.audit = audit; this.aggregator = aggregator; this.clock = clock;
    }

    public ReviewStepCompleteTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ContractReviewRunMaterialRepository materials, ContractReviewFindingRepository findings,
            ContractReviewEvidenceRepository evidence,
            ContractReviewClauseComparisonRepository typedComparisons, IdempotencyPort idempotency,
            OperationAuditPort audit, ReviewRunStatusAggregator aggregator, Clock clock) {
        this(runs, steps, attempts, materials, findings, evidence, typedComparisons,
                idempotency, null, audit, aggregator, clock);
    }

    public ReviewStepCompleteTransactionCoordinator(ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ContractReviewRunMaterialRepository materials, ContractReviewFindingRepository findings,
            ContractReviewEvidenceRepository evidence, IdempotencyPort idempotency,
            OperationAuditPort audit, ReviewRunStatusAggregator aggregator, Clock clock) {
        this(runs, steps, attempts, materials, findings, evidence, null, idempotency, null,
                audit, aggregator, clock);
    }

    @Transactional
    public synchronized CompletedExecution complete(
            ReviewStepStartTransactionCoordinator.StartedExecution started, StepComputationResult result) {
        var current = current(started);
        validateResult(started, result, current.attempt());
        Instant now = clock.instant();
        for (int index = 0; index < result.findings().size(); index++) {
            var finding = result.findings().get(index);
            ReviewPersistenceDiagnostics.observe(log, "FINDING_INSERT", index + 1,
                    () -> findings.insert(finding));
        }
        for (int index = 0; index < result.evidence().size(); index++) {
            var item = result.evidence().get(index);
            ReviewPersistenceDiagnostics.observe(log, "EVIDENCE_INSERT", index + 1,
                    () -> evidence.insert(item));
        }
        for (int index = 0; index < result.typedComparisons().size(); index++) {
            var comparison = result.typedComparisons().get(index);
            int ordinal = index + 1;
            ReviewPersistenceDiagnostics.observe(log, "TYPED_COMPARISON_INSERT", ordinal,
                    () -> typedComparisons.insert(comparison));
        }
        if (!result.modelCalls().isEmpty()) modelCalls.insertAll(result.modelCalls());
        ReviewStepExternalCallState externalState = current.attempt().externalCallState()
                == ReviewStepExternalCallState.REQUESTED
                ? ReviewStepExternalCallState.COMPLETED : ReviewStepExternalCallState.NOT_STARTED;
        var completedAttempt = new ContractReviewStepAttempt(current.attempt().attemptId(),
                current.attempt().runId(), current.attempt().stepId(), current.attempt().attemptNo(),
                current.attempt().operationIdentity(), ContractReviewStepStatus.COMPLETED,
                current.attempt().leaseOwner(), current.attempt().leaseUntil(), current.attempt().claimedAt(),
                externalState, current.attempt().externalRequestFingerprint(), result.providerRequestId(),
                result.provider(), result.model(), current.attempt().startedAt(), now, null,
                result.outputResourceType(), result.outputResourceId(), current.attempt().versionNo() + 1);
        ContractReviewStepAttempt nextAttempt = completedAttempt;
        completedAttempt = ReviewPersistenceDiagnostics.observe(log, "ATTEMPT_COMPLETE", 0,
                () -> attempts.transition(current.attempt(), nextAttempt));
        var completedStep = new ContractReviewStep(current.step().stepId(), current.step().runId(),
                current.step().stepType(), current.step().required(), ContractReviewStepStatus.COMPLETED,
                current.step().stepPolicyVersion(), current.step().currentAttemptNo(),
                result.outputResourceType(), result.outputResourceId(), null, current.step().startedAt(),
                now, current.step().versionNo() + 1);
        ContractReviewStep nextStep = completedStep;
        completedStep = ReviewPersistenceDiagnostics.observe(log, "STEP_COMPLETE", 0,
                () -> steps.transition(current.step(), nextStep));
        ContractReviewRun completedRun = ReviewPersistenceDiagnostics.observe(log, "RUN_AGGREGATE", 0,
                () -> transitionRun(current.run(), aggregator.status(
                        steps.findStepsByRunId(current.run().runId())), null, now));
        ReviewPersistenceDiagnostics.observe(log, "IDEMPOTENCY_COMPLETE", 0,
                () -> idempotency.markSucceeded(current.idempotency(), result.outputResourceType(),
                        result.outputResourceId().toString(), now));
        ContractReviewStep auditedStep = completedStep;
        ReviewPersistenceDiagnostics.observe(log, "AUDIT_COMPLETE", 0,
                () -> audit.append(ReviewStepStartTransactionCoordinator.audit(
                        "CONTRACT_REVIEW_STEP_COMPLETED", "complete", started.actor(),
                        completedRun, auditedStep, started.command().metadata(),
                        OperationAuditResultStatus.SUCCESS, "COMPLETED", now)));
        return new CompletedExecution(completedRun, completedStep, completedAttempt,
                result.findings(), result.evidence());
    }

    private Current current(ReviewStepStartTransactionCoordinator.StartedExecution started) {
        var run = runs.findByIdForUpdate(started.run().runId()).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review run is missing"));
        var step = steps.findStepById(started.step().stepId()).filter(value ->
                value.runId().equals(run.runId())).orElseThrow(() ->
                ReviewStepStartTransactionCoordinator.conflict("review step is missing"));
        var attempt = attempts.findAttemptById(started.attempt().attemptId()).filter(value ->
                value.runId().equals(run.runId()) && value.stepId().equals(step.stepId()))
                .orElseThrow(() -> ReviewStepStartTransactionCoordinator.conflict("review step attempt is missing"));
        if ((run.status() != ContractReviewRunStatus.RUNNING
                && run.status() != ContractReviewRunStatus.UNKNOWN)
                || step.status() != ContractReviewStepStatus.RUNNING
                || attempt.status() != ContractReviewStepStatus.RUNNING)
            throw ReviewStepStartTransactionCoordinator.conflict("review step is not running");
        var durableIdempotency = idempotency.findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, started.command().idempotencyKey())
                .filter(value -> value.id().equals(started.idempotency().id())
                        && value.status() == IdempotencyStatus.IN_PROGRESS)
                .orElseThrow(() -> ReviewStepStartTransactionCoordinator.conflict(
                        "review step idempotency identity is not in progress"));
        return new Current(run, step, attempt, durableIdempotency);
    }

    private void validateResult(ReviewStepStartTransactionCoordinator.StartedExecution started,
            StepComputationResult result, ContractReviewStepAttempt attempt) {
        if (started.step().stepType() == ContractReviewStepType.SEMANTIC_AI
                && (attempt.externalCallState() != ReviewStepExternalCallState.REQUESTED
                || attempt.externalRequestFingerprint() == null))
            throw ReviewStepStartTransactionCoordinator.conflict(
                    "semantic completion requires a durable external request identity");
        if (started.step().stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
            boolean navigationV2 = FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(result.outputResourceType());
            boolean legacy = FrozenF02ResultMapper.LEGACY_OUTPUT_RESOURCE_TYPE.equals(result.outputResourceType());
            if (!navigationV2 && !legacy)
                throw ReviewStepStartTransactionCoordinator.conflict("F02 output must reference AuditTask");
            if (legacy && (!result.findings().isEmpty() || !result.evidence().isEmpty()))
                throw ReviewStepStartTransactionCoordinator.conflict("legacy F02 output cannot contain navigation carriers");
            if (navigationV2 && result.findings().isEmpty())
                throw ReviewStepStartTransactionCoordinator.conflict("F02 navigation V2 requires carriers");
        } else if (!"REVIEW_FINDING_SET".equals(result.outputResourceType())
                || !attempt.attemptId().equals(result.outputResourceId())) {
            throw ReviewStepStartTransactionCoordinator.conflict("non-F02 output identity is invalid");
        }
        if (!findings.findFindingsByAttemptId(attempt.attemptId()).isEmpty()
                || !evidence.findEvidenceByAttemptId(attempt.attemptId()).isEmpty())
            throw ReviewStepStartTransactionCoordinator.conflict("review step result already exists");
        Set<UUID> findingIds = new HashSet<>();
        Set<Integer> findingOrdinals = new HashSet<>();
        for (var finding : result.findings()) {
            if (!finding.runId().equals(started.run().runId())
                    || !finding.stepId().equals(started.step().stepId())
                    || !finding.attemptId().equals(attempt.attemptId())
                    || !finding.policyVersion().equals(started.step().stepPolicyVersion())
                    || !findingIds.add(finding.findingId()) || !findingOrdinals.add(finding.ordinal()))
                throw ReviewStepStartTransactionCoordinator.conflict("finding ownership is invalid");
        }
        Set<String> evidenceOrdinals = new HashSet<>();
        var selected = materials.findMaterialsByRunId(started.run().runId());
        for (ContractReviewEvidence item : result.evidence()) {
            if (!item.runId().equals(started.run().runId())
                    || !item.stepId().equals(started.step().stepId())
                    || !item.attemptId().equals(attempt.attemptId())
                    || !findingIds.contains(item.findingId())
                    || !evidenceOrdinals.add(item.findingId() + ":" + item.ordinal())
                    || selected.stream().noneMatch(material -> exact(material, item)))
                throw ReviewStepStartTransactionCoordinator.conflict("evidence ownership is invalid");
        }
        validateTypedComparisons(started, attempt, result, findingIds, selected);
        validateModelCalls(started, attempt, result);
        if (started.step().stepType() == ContractReviewStepType.F02_DETERMINISTIC
                && FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(result.outputResourceType())) {
            for (var finding : result.findings()) {
                if (finding.findingSchemaVersion() != FrozenF02ResultMapper.FINDING_SCHEMA_VERSION
                        || !FrozenF02ResultMapper.FINDING_TYPE.equals(finding.findingType())
                        || finding.source() != com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource.DETERMINISTIC
                        || !finding.policyVersion().equals(started.step().stepPolicyVersion()))
                    throw ReviewStepStartTransactionCoordinator.conflict("F02 finding schema is invalid");
                List<ContractReviewEvidence> owned = result.evidence().stream()
                        .filter(value -> value.findingId().equals(finding.findingId())).toList();
                if (owned.isEmpty()) {
                    if (finding.attentionCode() == null
                            || !finding.attentionCode().startsWith("SOURCE_UNAVAILABLE:"))
                        throw ReviewStepStartTransactionCoordinator.conflict("F02 evidence absence is unexplained");
                } else if (owned.stream().anyMatch(value -> FrozenF02ResultMapper.SUPPLIER_WITNESS_POLICY_VERSION
                        .equals(value.validationPolicyVersion()))) {
                    // Reopen the immutable snapshot, never current Contract or extraction state.
                    // Credit-code-first comparisons retain their original v2/exact-two contract.
                    var frozen = new ContractSnapshotDecoder(new ContractSnapshotCanonicalizer()).decodeAndVerify(started.run());
                    if (!started.run().ruleVersion().equals(started.step().stepPolicyVersion())
                            || frozen.awardNotice() == null
                            || frozen.partyBCreditCode() != null && !frozen.partyBCreditCode().isBlank()
                                && frozen.awardNotice().winningBidderCreditCode() != null
                                && !frozen.awardNotice().winningBidderCreditCode().isBlank()
                            || selected.stream().anyMatch(material -> !material.runId().equals(started.run().runId())
                                || !material.projectId().equals(started.run().projectId())))
                        throw ReviewStepStartTransactionCoordinator.conflict("F02 supplier witness policy is invalid");
                    FrozenF02ResultMapper.requireSupplierWitnessCertificate(started.step(), finding, owned, selected);
                } else if (finding.attentionCode() != null
                        && !"SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE".equals(finding.attentionCode())
                        || owned.stream().anyMatch(value ->
                        value.locatorSchemaVersion() != FrozenF02ResultMapper.LOCATOR_SCHEMA_VERSION
                                || !FrozenF02ResultMapper.VALIDATION_POLICY_VERSION.equals(
                                        value.validationPolicyVersion()))) {
                    throw ReviewStepStartTransactionCoordinator.conflict("F02 evidence schema is invalid");
                }
            }
        }
    }

    private void validateModelCalls(ReviewStepStartTransactionCoordinator.StartedExecution started,
            ContractReviewStepAttempt attempt, StepComputationResult result) {
        if (result.modelCalls().isEmpty()) return;
        if (started.step().stepType() != ContractReviewStepType.SEMANTIC_AI || modelCalls == null
                || attempt.externalRequestFingerprint() == null) {
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit owner is invalid");
        }
        Set<Integer> ordinals = new HashSet<>();
        if (result.modelCalls().stream().anyMatch(call -> call.factExtractionAttemptId() != null
                || !attempt.attemptId().equals(call.reviewStepAttemptId())
                || attempt.attemptNo() != call.attemptNo() || call.scope() != null
                || call.batchStatus() != null
                || !attempt.externalRequestFingerprint().equals(call.requestFingerprint())
                || !ordinals.add(call.callOrdinal()))) {
            throw ReviewStepStartTransactionCoordinator.conflict("model call audit identity is invalid");
        }
    }

    private void validateTypedComparisons(ReviewStepStartTransactionCoordinator.StartedExecution started,
            ContractReviewStepAttempt attempt, StepComputationResult result, Set<UUID> findingIds,
            List<com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial> selected) {
        boolean typedStep = started.step().stepType() == ContractReviewStepType.BREACH
                || started.step().stepType() == ContractReviewStepType.TERM;
        if (!typedStep && !result.typedComparisons().isEmpty())
            throw ReviewStepStartTransactionCoordinator.conflict("typed comparison step is invalid");
        if (!result.typedComparisons().isEmpty() && typedComparisons == null)
            throw ReviewStepStartTransactionCoordinator.conflict("typed comparison repository is unavailable");
        Set<UUID> comparisonIds = new HashSet<>();
        Set<String> comparisonKeys = new HashSet<>();
        var evidenceById = result.evidence().stream().collect(java.util.stream.Collectors.toMap(
                ContractReviewEvidence::evidenceId, java.util.function.Function.identity()));
        Set<UUID> frozenFileIds = selected.stream()
                .map(com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial::fileId)
                .collect(java.util.stream.Collectors.toSet());
        for (ContractReviewClauseComparison comparison : result.typedComparisons()) {
            String expectedKind = started.step().stepType() == ContractReviewStepType.BREACH
                    ? "BREACH" : started.step().stepType() == ContractReviewStepType.TERM ? "TERM" : null;
            if (!comparison.runId().equals(started.run().runId())
                    || !comparison.stepId().equals(started.step().stepId())
                    || !comparison.attemptId().equals(attempt.attemptId())
                    || !java.util.Objects.equals(expectedKind, comparison.kind())
                    || !findingIds.contains(comparison.findingId())
                    || comparison.schemaVersion() != 2
                    || !comparisonIds.add(comparison.comparisonId())
                    || !comparisonKeys.add(comparison.comparisonKey())
                    || comparison.values().stream().anyMatch(value -> {
                        if (!value.runId().equals(comparison.runId())
                                || !frozenFileIds.contains(value.sourceFileId())) return true;
                        return value.evidenceIds().stream().anyMatch(evidenceId -> {
                            ContractReviewEvidence owned = evidenceById.get(evidenceId);
                            return owned == null || !owned.findingId().equals(comparison.findingId())
                                    || !owned.fileId().equals(value.sourceFileId())
                                    || !"COMPARISON".equals(owned.evidenceRole());
                        });
                    }))
                throw ReviewStepStartTransactionCoordinator.conflict("typed comparison ownership is invalid");
        }
        if (typedStep) {
            Set<UUID> required = result.findings().stream()
                    .filter(finding -> finding.findingSchemaVersion() == 2
                            && finding.findingType().endsWith("_COMPARISON"))
                    .map(com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding::findingId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<UUID> present = result.typedComparisons().stream()
                    .map(ContractReviewClauseComparison::findingId)
                    .collect(java.util.stream.Collectors.toSet());
            if (!required.equals(present))
                throw ReviewStepStartTransactionCoordinator.conflict("typed comparison set is incomplete");
        }
    }

    private static boolean exact(com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial material,
            ContractReviewEvidence evidence) {
        return material.fileId().equals(evidence.fileId())
                && material.fileSha256().equals(evidence.fileSha256())
                && material.parseResultId().equals(evidence.parseResultId())
                && material.parseVersion() == evidence.parseVersion()
                && material.canonicalTextSha256().equals(evidence.canonicalTextSha256());
    }

    private ContractReviewRun transitionRun(ContractReviewRun run, ContractReviewRunStatus status,
            String safeReason, Instant now) {
        if (run.status() == ContractReviewRunStatus.UNKNOWN
                && status == ContractReviewRunStatus.UNKNOWN) return run;
        var next = new ContractReviewRun(run.runId(), run.projectId(), run.contractId(), run.contractVersion(),
                run.sourceCandidateId(), run.sourceConfirmationId(), run.runSchemaVersion(),
                run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                run.ruleVersion(), run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(), status,
                run.requestedAt(), run.startedAt(), status == ContractReviewRunStatus.COMPLETED ? now : null,
                status == ContractReviewRunStatus.FAILED ? now : null,
                status == ContractReviewRunStatus.UNKNOWN ? now : null, safeReason, run.versionNo() + 1);
        return runs.transition(run, next);
    }

    public record CompletedExecution(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt,
            List<com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding> findings,
            List<ContractReviewEvidence> evidence) {
        public CompletedExecution { findings = List.copyOf(findings); evidence = List.copyOf(evidence); }
    }
    private record Current(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt, com.fuquan.audit.common.application.port.IdempotencyRecord idempotency) { }
}
