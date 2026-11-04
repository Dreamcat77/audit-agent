package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewEvidenceRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewFindingRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewClauseComparisonRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs only persisted ReviewStep output; it never invokes an executor or materializes data. */
@Service
public class ReviewStepOutputReadService {
    private static final Comparator<ContractReviewStep> STEP_ORDER = Comparator
            .comparingInt((ContractReviewStep value) -> value.stepType().ordinal())
            .thenComparing(value -> value.stepId().toString());
    private static final Comparator<ContractReviewStepAttempt> ATTEMPT_ORDER = Comparator
            .comparingInt(ContractReviewStepAttempt::attemptNo)
            .thenComparing(value -> value.attemptId().toString());
    private static final Comparator<ContractReviewFinding> FINDING_ORDER = Comparator
            .comparingInt(ContractReviewFinding::ordinal)
            .thenComparing(value -> value.findingId().toString());
    private static final Comparator<AuditCheckResult> CHECK_ORDER = Comparator
            .comparing(AuditCheckResult::ruleCode)
            .thenComparing(AuditCheckResult::itemKey, Comparator.nullsFirst(String::compareTo))
            .thenComparing(value -> value.id().toString());

    private final ContractReviewRunService runs;
    private final ContractReviewFindingRepository findings;
    private final ContractReviewEvidenceRepository evidence;
    private final ContractReviewClauseComparisonRepository typedComparisons;
    private final AuditTaskRepository auditTasks;
    private final AuditCheckResultRepository checks;
    private final IdempotencyPort idempotency;
    private final com.fuquan.audit.contract.application.reviewrun.semantic.SemanticMonetaryObservationReader monetaryObservations;

    public ReviewStepOutputReadService(ContractReviewRunService runs,
            ContractReviewFindingRepository findings, ContractReviewEvidenceRepository evidence,
            ContractReviewClauseComparisonRepository typedComparisons,
            AuditTaskRepository auditTasks, AuditCheckResultRepository checks,
            IdempotencyPort idempotency) {
        this(runs, findings, evidence, typedComparisons, auditTasks, checks, idempotency, null);
    }

    @Autowired
    public ReviewStepOutputReadService(ContractReviewRunService runs,
            ContractReviewFindingRepository findings, ContractReviewEvidenceRepository evidence,
            ContractReviewClauseComparisonRepository typedComparisons,
            AuditTaskRepository auditTasks, AuditCheckResultRepository checks,
            IdempotencyPort idempotency,
            com.fuquan.audit.contract.application.reviewrun.semantic.SemanticMonetaryObservationReader monetaryObservations) {
        this.runs = runs;
        this.findings = findings;
        this.evidence = evidence;
        this.typedComparisons = typedComparisons;
        this.auditTasks = auditTasks;
        this.checks = checks;
        this.idempotency = idempotency;
        this.monetaryObservations = monetaryObservations;
    }

    public ReviewStepOutputReadService(ContractReviewRunService runs,
            ContractReviewFindingRepository findings, ContractReviewEvidenceRepository evidence,
            AuditTaskRepository auditTasks, AuditCheckResultRepository checks,
            IdempotencyPort idempotency) {
        this(runs, findings, evidence, null, auditTasks, checks, idempotency);
    }

    @Transactional(readOnly = true)
    public StepOutput getStep(UUID projectId, UUID contractId, UUID runId, UUID stepId) {
        Projection projection = load(projectId, contractId, runId);
        return projection.steps().stream().filter(value -> value.step().stepId().equals(stepId))
                .findFirst().orElseThrow(ReviewStepOutputReadService::notFound);
    }

    @Transactional(readOnly = true)
    public RunOutput getRun(UUID projectId, UUID contractId, UUID runId) {
        Projection projection = load(projectId, contractId, runId);
        try {
            return new RunOutput(projection.manifest(), projection.steps(), monetaryObservations == null ? null
                    : monetaryObservations.read(projection.manifest(), projection.steps()));
        } catch (IllegalStateException invalid) { throw conflict(); }
    }

    private Projection load(UUID projectId, UUID contractId, UUID runId) {
        ContractReviewRunCreateResult manifest = runs.get(projectId, contractId, runId);
        if (manifest == null || !manifest.run().runId().equals(runId)
                || !manifest.run().projectId().equals(projectId)
                || !manifest.run().contractId().equals(contractId)) {
            throw notFound();
        }
        Map<UUID, ContractReviewStep> stepById = unique(manifest.steps(), ContractReviewStep::stepId);
        Map<UUID, ContractReviewStepAttempt> attemptById = unique(
                manifest.attempts(), ContractReviewStepAttempt::attemptId);
        Map<UUID, ContractReviewFinding> findingById = validateFindings(
                manifest, stepById, attemptById, findings.findFindingsByRunId(runId));
        List<ContractReviewEvidence> durableEvidence = validateEvidence(
                manifest, findingById, evidence.findEvidenceByRunId(runId));
        List<ContractReviewClauseComparison> durableTyped = typedComparisons == null ? List.of()
                : validateTyped(manifest, stepById, findingById, durableEvidence,
                        typedComparisons.findByRunId(runId));
        List<StepOutput> outputs = manifest.steps().stream().sorted(STEP_ORDER)
                .map(step -> output(manifest.run(), step, manifest.attempts(), findingById.values(),
                        durableEvidence, durableTyped, manifest.materials()))
                .toList();
        return new Projection(manifest, outputs);
    }

    private StepOutput output(ContractReviewRun run, ContractReviewStep step,
            List<ContractReviewStepAttempt> manifestAttempts,
            java.util.Collection<ContractReviewFinding> durableFindings,
            List<ContractReviewEvidence> durableEvidence,
            List<ContractReviewClauseComparison> durableTyped,
            List<ContractReviewRunMaterial> frozenMaterials) {
        List<ContractReviewStepAttempt> stepAttempts = manifestAttempts.stream()
                .filter(value -> value.stepId().equals(step.stepId())).sorted(ATTEMPT_ORDER).toList();
        List<ContractReviewFinding> stepFindings = durableFindings.stream()
                .filter(value -> value.stepId().equals(step.stepId())).sorted(FINDING_ORDER).toList();
        Map<UUID, ContractReviewFinding> stepFindingById = stepFindings.stream()
                .collect(Collectors.toMap(ContractReviewFinding::findingId, Function.identity()));
        List<ContractReviewEvidence> stepEvidence = durableEvidence.stream()
                .filter(value -> value.stepId().equals(step.stepId()))
                .sorted(evidenceOrder(stepFindingById)).toList();
        List<ContractReviewClauseComparison> stepTyped = durableTyped.stream()
                .filter(value -> value.stepId().equals(step.stepId()))
                .sorted(Comparator.comparing(ContractReviewClauseComparison::comparisonKey)).toList();

        if (step.stepType() == ContractReviewStepType.MATERIAL_VALIDATION) {
            if (!stepAttempts.isEmpty() || !stepFindings.isEmpty() || !stepEvidence.isEmpty()
                    || !stepTyped.isEmpty()) throw conflict();
            return new StepOutput(run, step, stepAttempts, null, List.of(), stepFindings, stepEvidence,
                    stepTyped);
        }
        ContractReviewStepAttempt current = stepAttempts.stream()
                .filter(value -> value.attemptNo() == step.currentAttemptNo()).findFirst()
                .orElseThrow(ReviewStepOutputReadService::conflict);
        if (current.runId().equals(run.runId()) && current.status() == step.status()) {
            // Continue below; both identities and state must agree before output may be exposed.
        } else {
            throw conflict();
        }
        if (step.status() != ContractReviewStepStatus.COMPLETED) {
            if (step.outputResourceType() != null || step.outputResourceId() != null
                    || current.outputResourceType() != null || current.outputResourceId() != null
                    || !stepFindings.isEmpty() || !stepEvidence.isEmpty() || !stepTyped.isEmpty()) throw conflict();
            return new StepOutput(run, step, stepAttempts, null, List.of(), List.of(), List.of(), List.of());
        }
        if (step.outputResourceType() == null || step.outputResourceId() == null
                || !Objects.equals(step.outputResourceType(), current.outputResourceType())
                || !Objects.equals(step.outputResourceId(), current.outputResourceId())) throw conflict();
        if (step.stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
            F02Output durable = validateF02Output(
                    run, step, current, step.outputResourceId(), null);
            if (FrozenF02ResultMapper.LEGACY_OUTPUT_RESOURCE_TYPE.equals(step.outputResourceType())) {
                if (!stepFindings.isEmpty() || !stepEvidence.isEmpty()) throw conflict();
            } else if (FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(step.outputResourceType())) {
                if (stepEvidence.stream().anyMatch(item -> FrozenF02ResultMapper.SUPPLIER_WITNESS_POLICY_VERSION
                        .equals(item.validationPolicyVersion())) && !run.ruleVersion().equals(step.stepPolicyVersion()))
                    throw conflict();
                FrozenF02ResultMapper.requireValidProjection(step, durable.task().id(), durable.checks(),
                        stepFindings, stepEvidence, frozenMaterials);
            } else throw conflict();
            return new StepOutput(run, step, stepAttempts, durable.task(), durable.checks(),
                    stepFindings, stepEvidence, stepTyped);
        }
        if (!"REVIEW_FINDING_SET".equals(step.outputResourceType())
                || !step.outputResourceId().equals(current.attemptId())) throw conflict();
        if ((step.stepType() == ContractReviewStepType.BREACH || step.stepType() == ContractReviewStepType.TERM)) {
            Set<UUID> required = stepFindings.stream().filter(value -> value.findingSchemaVersion() == 2
                            && value.findingType().endsWith("_COMPARISON"))
                    .map(ContractReviewFinding::findingId).collect(Collectors.toSet());
            Set<UUID> present = stepTyped.stream().map(ContractReviewClauseComparison::findingId)
                    .collect(Collectors.toSet());
            if (!required.equals(present)) throw conflict();
        } else if (!stepTyped.isEmpty()) throw conflict();
        return new StepOutput(run, step, stepAttempts, null, List.of(), stepFindings, stepEvidence,
                stepTyped);
    }

    /** Proves exact ReviewRun attempt ownership before reading an existing F02 result. */
    @Transactional(readOnly = true)
    public F02Output validateF02Output(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt, UUID taskId, String expectedActorId) {
        String internalKey = FrozenF02ReviewStepExecutor.internalKey(
                run.runId(), step.stepId(), attempt.attemptId());
        IdempotencyRecord operation = idempotency.findByScopeAndKey(
                ContractReviewService.IDEMPOTENCY_SCOPE, internalKey).filter(value ->
                        ContractReviewService.IDEMPOTENCY_SCOPE.equals(value.scope())
                                && internalKey.equals(value.idempotencyKey())
                                && value.status() == IdempotencyStatus.SUCCEEDED
                                && "CONTRACT".equals(value.resourceType())
                                && run.contractId().toString().equals(value.resourceId())
                                && "AUDIT_TASK".equals(value.resultResourceType())
                                && taskId.toString().equals(value.resultResourceId())
                                && (expectedActorId == null
                                        || expectedActorId.equals(value.actorId())))
                .orElseThrow(ReviewStepOutputReadService::conflict);
        F02Output output = validateF02Task(run, taskId);
        if (!Objects.equals(operation.actorId(), output.task().createdBy())) throw conflict();
        return output;
    }

    /** Validates a not-yet-persisted F02 carrier set against its durable AuditCheckResults. */
    @Transactional(readOnly = true)
    public F02Output validateF02Publication(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt, StepComputationResult result, String expectedActorId) {
        F02Output output = validateF02Output(run, step, attempt, result.outputResourceId(), expectedActorId);
        if (FrozenF02ResultMapper.LEGACY_OUTPUT_RESOURCE_TYPE.equals(result.outputResourceType())) {
            if (!result.findings().isEmpty() || !result.evidence().isEmpty()) throw conflict();
        } else if (FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(result.outputResourceType())) {
            List<ContractReviewRunMaterial> materials = null;
            if (result.evidence().stream().anyMatch(item -> FrozenF02ResultMapper.SUPPLIER_WITNESS_POLICY_VERSION
                    .equals(item.validationPolicyVersion()))) {
                var manifest = runs.get(run.projectId(), run.contractId(), run.runId());
                if (manifest == null || !manifest.run().runId().equals(run.runId())
                        || !manifest.run().projectId().equals(run.projectId())
                        || !manifest.run().contractId().equals(run.contractId())
                        || !manifest.run().inputSnapshotIdentity().equals(run.inputSnapshotIdentity())
                        || !manifest.run().ruleVersion().equals(step.stepPolicyVersion())) throw conflict();
                materials = manifest.materials();
            }
            FrozenF02ResultMapper.requireValidProjection(step, output.task().id(), output.checks(),
                    result.findings(), result.evidence(), materials);
        } else throw conflict();
        return output;
    }

    private F02Output validateF02Task(ContractReviewRun run, UUID taskId) {
        AuditTask task = auditTasks.findById(taskId)
                .orElseThrow(ReviewStepOutputReadService::conflict);
        if (!"CONTRACT".equals(task.sourceType()) || !task.sourceId().equals(run.contractId())
                || task.sourceVersion() != run.contractVersion()
                || !ContractReviewService.REVIEW_TYPE.equals(task.reviewType())
                || task.status() != AuditTaskStatus.DONE) throw conflict();
        List<AuditCheckResult> taskChecks = checks.findByTaskId(task.id());
        if (taskChecks.stream().anyMatch(value -> !value.taskId().equals(task.id()))) throw conflict();
        taskChecks = taskChecks.stream().sorted(CHECK_ORDER).toList();
        if (taskChecks.isEmpty()) throw conflict();
        return new F02Output(task, taskChecks);
    }

    private static Map<UUID, ContractReviewFinding> validateFindings(ContractReviewRunCreateResult manifest,
            Map<UUID, ContractReviewStep> steps, Map<UUID, ContractReviewStepAttempt> attempts,
            List<ContractReviewFinding> rows) {
        Map<UUID, ContractReviewFinding> byId = unique(rows, ContractReviewFinding::findingId);
        Set<String> ordinals = new HashSet<>();
        for (var finding : rows) {
            ContractReviewStep step = steps.get(finding.stepId());
            ContractReviewStepAttempt attempt = attempts.get(finding.attemptId());
            if (!finding.runId().equals(manifest.run().runId()) || step == null || attempt == null
                    || !attempt.runId().equals(finding.runId())
                    || !attempt.stepId().equals(finding.stepId())
                    || attempt.status() != ContractReviewStepStatus.COMPLETED
                    || !ordinals.add(finding.attemptId() + ":" + finding.ordinal())) throw conflict();
            if (step.stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
                if (!FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(attempt.outputResourceType())
                        || finding.findingSchemaVersion() != FrozenF02ResultMapper.FINDING_SCHEMA_VERSION)
                    throw conflict();
            } else if (step.stepType() == ContractReviewStepType.MATERIAL_VALIDATION
                    || !"REVIEW_FINDING_SET".equals(attempt.outputResourceType())
                    || !attempt.attemptId().equals(attempt.outputResourceId())) throw conflict();
        }
        return byId;
    }

    private static List<ContractReviewEvidence> validateEvidence(ContractReviewRunCreateResult manifest,
            Map<UUID, ContractReviewFinding> findings, List<ContractReviewEvidence> rows) {
        unique(rows, ContractReviewEvidence::evidenceId);
        Set<String> ordinals = new HashSet<>();
        for (var item : rows) {
            ContractReviewFinding finding = findings.get(item.findingId());
            if (!item.runId().equals(manifest.run().runId()) || finding == null
                    || !finding.runId().equals(item.runId()) || !finding.stepId().equals(item.stepId())
                    || !finding.attemptId().equals(item.attemptId())
                    || !ordinals.add(item.findingId() + ":" + item.ordinal())
                    || manifest.materials().stream().noneMatch(material -> sameParseTuple(material, item)))
                throw conflict();
        }
        return List.copyOf(rows);
    }

    private static List<ContractReviewClauseComparison> validateTyped(
            ContractReviewRunCreateResult manifest, Map<UUID, ContractReviewStep> steps,
            Map<UUID, ContractReviewFinding> findings, List<ContractReviewEvidence> evidence,
            List<ContractReviewClauseComparison> rows) {
        Map<UUID, ContractReviewEvidence> evidenceById = unique(evidence,
                ContractReviewEvidence::evidenceId);
        Set<UUID> ids = new HashSet<>(); Set<String> keys = new HashSet<>();
        for (ContractReviewClauseComparison comparison : rows) {
            ContractReviewFinding finding = findings.get(comparison.findingId());
            ContractReviewStep step = steps.get(comparison.stepId());
            String expectedKind = step == null ? null
                    : step.stepType() == ContractReviewStepType.BREACH ? "BREACH"
                    : step.stepType() == ContractReviewStepType.TERM ? "TERM" : null;
            if (finding == null || finding.findingSchemaVersion() != 2
                    || !comparison.runId().equals(manifest.run().runId())
                    || !finding.runId().equals(comparison.runId())
                    || !finding.stepId().equals(comparison.stepId())
                    || !finding.attemptId().equals(comparison.attemptId())
                    || !java.util.Objects.equals(expectedKind, comparison.kind())
                    || !ids.add(comparison.comparisonId())
                    || !keys.add(comparison.attemptId() + ":" + comparison.comparisonKey())
                    || comparison.values().stream().anyMatch(value -> {
                        if (!value.runId().equals(comparison.runId())
                                || manifest.materials().stream().noneMatch(material ->
                                        material.fileId().equals(value.sourceFileId()))) return true;
                        return value.evidenceIds().stream().anyMatch(evidenceId -> {
                            ContractReviewEvidence item = evidenceById.get(evidenceId);
                            return item == null || !item.findingId().equals(comparison.findingId())
                                    || !item.fileId().equals(value.sourceFileId())
                                    || !"COMPARISON".equals(item.evidenceRole());
                        });
                    })) throw conflict();
        }
        return List.copyOf(rows);
    }

    private static boolean sameParseTuple(ContractReviewRunMaterial material, ContractReviewEvidence item) {
        return material.fileId().equals(item.fileId())
                && material.fileSha256().equals(item.fileSha256())
                && material.parseResultId().equals(item.parseResultId())
                && material.parseVersion() == item.parseVersion()
                && material.canonicalTextSha256().equals(item.canonicalTextSha256());
    }

    private static Comparator<ContractReviewEvidence> evidenceOrder(
            Map<UUID, ContractReviewFinding> findings) {
        return Comparator.comparingInt((ContractReviewEvidence value) -> {
            ContractReviewFinding finding = findings.get(value.findingId());
            if (finding == null) throw conflict();
            return finding.ordinal();
        }).thenComparing(value -> value.findingId().toString())
                .thenComparingInt(ContractReviewEvidence::ordinal)
                .thenComparing(value -> value.evidenceId().toString());
    }

    private static <T> Map<UUID, T> unique(List<T> rows, Function<T, UUID> identity) {
        Map<UUID, T> values = new HashMap<>();
        for (T row : rows) if (values.putIfAbsent(identity.apply(row), row) != null) throw conflict();
        return values;
    }

    private static BusinessException notFound() {
        return new BusinessException(BusinessErrorCode.NOT_FOUND, "review step not found");
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "review step durable output cannot be verified");
    }

    public record StepOutput(ContractReviewRun run, ContractReviewStep step,
            List<ContractReviewStepAttempt> attempts, AuditTask auditTask,
            List<AuditCheckResult> auditCheckResults, List<ContractReviewFinding> findings,
            List<ContractReviewEvidence> evidence,
            List<ContractReviewClauseComparison> typedComparisons) {
        public StepOutput {
            attempts = List.copyOf(attempts);
            auditCheckResults = List.copyOf(auditCheckResults);
            findings = List.copyOf(findings);
            evidence = List.copyOf(evidence);
            typedComparisons = List.copyOf(typedComparisons);
        }
        public StepOutput(ContractReviewRun run, ContractReviewStep step,
                List<ContractReviewStepAttempt> attempts, AuditTask auditTask,
                List<AuditCheckResult> auditCheckResults, List<ContractReviewFinding> findings,
                List<ContractReviewEvidence> evidence) {
            this(run, step, attempts, auditTask, auditCheckResults, findings, evidence, List.of());
        }
    }

    public record RunOutput(ContractReviewRunCreateResult manifest, List<StepOutput> steps,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            com.fuquan.audit.contract.application.ai.ContractMonetaryObservation monetaryObservation) {
        public RunOutput { steps = List.copyOf(steps); }
        public RunOutput(ContractReviewRunCreateResult manifest, List<StepOutput> steps) { this(manifest, steps, null); }
    }

    public record F02Output(AuditTask task, List<AuditCheckResult> checks) {
        public F02Output { checks = List.copyOf(checks); }
    }

    private record Projection(ContractReviewRunCreateResult manifest, List<StepOutput> steps) { }
}
