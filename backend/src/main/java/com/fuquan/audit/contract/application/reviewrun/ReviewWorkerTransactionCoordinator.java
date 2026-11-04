package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepStartTransactionCoordinator.StartedExecution;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepStartTransactionCoordinator.StartCommand;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository.Candidate;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository.LeaseCondition;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Plan;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Unit;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.UnitStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** All mutation methods are short independent transactions. Executors never run in this class. */
public class ReviewWorkerTransactionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReviewWorkerTransactionCoordinator.class);
    private static final String CHECKPOINT_POLICY = ContractReviewExecutionPolicyResolver.DURABLE_MODEL_POLICY_VERSION;
    private static final ObjectMapper CHECKPOINT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ContractReviewWorkerRepository workers;
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final ContractReviewExecutionPolicyResolver policies;
    private final FrozenReviewInputLoader inputs;
    private final ReviewStepCompleteTransactionCoordinator completes;
    private final ReviewStepOutputReadService outputs;
    private final FrozenF02ResultMapper f02Mapper;
    private final ContractModelCallAuditRepository modelCalls;
    private final SemanticCheckpointRepository semanticCheckpoints;
    private final ReviewRunStatusAggregator aggregator;
    private final Clock clock;
    private final Duration leaseDuration;
    private final ReviewWorkerLeasePolicy leases = new ReviewWorkerLeasePolicy();

    public ReviewWorkerTransactionCoordinator(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency,
            OperationAuditPort audit, ContractReviewExecutionPolicyResolver policies,
            FrozenReviewInputLoader inputs, ReviewStepCompleteTransactionCoordinator completes,
            ReviewStepOutputReadService outputs, ReviewRunStatusAggregator aggregator,
            Clock clock, Duration leaseDuration) {
        this(workers, runs, steps, attempts, idempotency, audit, policies, inputs, completes,
                outputs, aggregator, clock, leaseDuration, null);
    }

    public ReviewWorkerTransactionCoordinator(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency,
            OperationAuditPort audit, ContractReviewExecutionPolicyResolver policies,
            FrozenReviewInputLoader inputs, ReviewStepCompleteTransactionCoordinator completes,
            ReviewStepOutputReadService outputs, ReviewRunStatusAggregator aggregator,
            Clock clock, Duration leaseDuration, FrozenF02ResultMapper f02Mapper) {
        this(workers, runs, steps, attempts, idempotency, audit, policies, inputs, completes,
                outputs, aggregator, clock, leaseDuration, f02Mapper, null);
    }

    public ReviewWorkerTransactionCoordinator(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency,
            OperationAuditPort audit, ContractReviewExecutionPolicyResolver policies,
            FrozenReviewInputLoader inputs, ReviewStepCompleteTransactionCoordinator completes,
            ReviewStepOutputReadService outputs, ReviewRunStatusAggregator aggregator,
            Clock clock, Duration leaseDuration, FrozenF02ResultMapper f02Mapper,
            ContractModelCallAuditRepository modelCalls) {
        this(workers, runs, steps, attempts, idempotency, audit, policies, inputs, completes,
                outputs, aggregator, clock, leaseDuration, f02Mapper, modelCalls, null);
    }

    public ReviewWorkerTransactionCoordinator(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency,
            OperationAuditPort audit, ContractReviewExecutionPolicyResolver policies,
            FrozenReviewInputLoader inputs, ReviewStepCompleteTransactionCoordinator completes,
            ReviewStepOutputReadService outputs, ReviewRunStatusAggregator aggregator,
            Clock clock, Duration leaseDuration, FrozenF02ResultMapper f02Mapper,
            ContractModelCallAuditRepository modelCalls, SemanticCheckpointRepository semanticCheckpoints) {
        if (leaseDuration.compareTo(Duration.ofSeconds(10)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException("worker lease must be 10 seconds..30 minutes");
        this.workers = workers; this.runs = runs; this.steps = steps; this.attempts = attempts;
        this.idempotency = idempotency; this.audit = audit; this.policies = policies;
        this.inputs = inputs; this.completes = completes; this.outputs = outputs; this.f02Mapper = f02Mapper;
        this.modelCalls = modelCalls;
        this.semanticCheckpoints = semanticCheckpoints;
        this.aggregator = aggregator; this.clock = clock; this.leaseDuration = leaseDuration;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartedExecution claim(Candidate candidate, String workerId) {
        State state = load(candidate);
        if ((state.run.status() != ContractReviewRunStatus.PENDING
                && state.run.status() != ContractReviewRunStatus.RUNNING)
                || state.step.status() != ContractReviewStepStatus.PENDING) throw conflict();
        inputs.load(state.run.projectId(), state.run.contractId(), state.run.runId());
        List<ContractRuleConfiguration> configurations = configurations(state);
        CurrentUser actor = businessActor(state.run);
        Instant now = clock.instant();
        var next = leases.claim(state.attempt, workerId, now, now.plus(leaseDuration));
        StartCommand command = command(state);
        var claim = idempotency.claim(new IdempotencyRequest(ReviewStepStartTransactionCoordinator.SCOPE,
                command.idempotencyKey(), actor.userId(), "CONTRACT_REVIEW_STEP", state.step.stepId().toString(),
                ReviewStepStartTransactionCoordinator.fingerprint(actor, state.run, state.step, state.attempt),
                "CONTRACT_REVIEW_STEP_ATTEMPT", state.attempt.attemptId().toString(),
                command.metadata().requestId(), command.metadata().traceId(), now));
        if (!claim.acquired()) throw conflict();
        try {
            next = workers.compareAndSetAttempt(state.attempt, next, LeaseCondition.PENDING, now);
            var step = steps.transition(state.step, step(state.step, ContractReviewStepStatus.RUNNING, null, now));
            var run = state.run.status() == ContractReviewRunStatus.PENDING
                    ? runs.transition(state.run, run(state.run, ContractReviewRunStatus.RUNNING, null, now)) : state.run;
            var execution = new StartedExecution(run, step, next, claim.record(), actor, command, configurations);
            audit(execution, "CLAIMED", OperationAuditResultStatus.SUCCESS, now);
            return execution;
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartedExecution heartbeat(StartedExecution execution) {
        State current = current(execution);
        Instant now = clock.instant();
        var next = leases.heartbeat(current.attempt, execution.attempt().leaseOwner(),
                execution.attempt().versionNo(), now, now.plus(leaseDuration));
        return replace(execution, current, workers.compareAndSetAttempt(
                current.attempt, next, LeaseCondition.ACTIVE, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartedExecution markRequested(StartedExecution execution, String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid external request fingerprint");
        State current = current(execution);
        Instant now = clock.instant();
        fence(execution, current, now);
        if (current.attempt.externalCallState() != ReviewStepExternalCallState.NOT_STARTED) throw conflict();
        var next = nextAttempt(current.attempt, current.attempt.status(), current.attempt.leaseOwner(),
                current.attempt.leaseUntil(), ReviewStepExternalCallState.REQUESTED, fingerprint, null, null);
        return replace(execution, current,
                workers.compareAndSetAttempt(current.attempt, next, LeaseCondition.ACTIVE, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(StartedExecution execution, StepComputationResult result) {
        completeFenced(execution, result);
    }

    private void completeFenced(StartedExecution execution, StepComputationResult result) {
        State current = current(execution);
        Instant now = clock.instant();
        fence(execution, current, now);
        if (current.step.stepType() == ContractReviewStepType.F02_DETERMINISTIC)
            outputs.validateF02Publication(current.run, current.step, current.attempt,
                    result, current.run.requestedBy());
        // This conditional write holds the attempt row lock until the SAME transaction commits.
        // The existing result validator/publication joins it; heartbeat/recovery cannot interleave.
        var locked = ReviewPersistenceDiagnostics.observe(log, "ATTEMPT_FENCE", 0,
                () -> workers.compareAndSetAttempt(current.attempt,
                        nextAttempt(current.attempt, current.attempt.status(),
                                current.attempt.leaseOwner(), current.attempt.leaseUntil(),
                                current.attempt.externalCallState(),
                                current.attempt.externalRequestFingerprint(), null, null),
                        LeaseCondition.ACTIVE, now));
        var fenced = replace(execution, current, locked);
        completes.complete(fenced, result);
        audit(fenced, "COMPLETED", OperationAuditResultStatus.SUCCESS, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close(StartedExecution execution, ContractReviewStepStatus status, String reason) {
        close(execution, status, reason, List.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close(StartedExecution execution, ContractReviewStepStatus status, String reason,
            List<ContractModelCallAudit> calls) {
        State current = current(execution);
        Instant now = clock.instant();
        fence(execution, current, now);
        var audits = List.copyOf(calls);
        validateCalls(current, status, audits);
        closeState(execution, current, status, reason, LeaseCondition.ACTIVE, now, audits);
    }

    private void validateCalls(State current, ContractReviewStepStatus status,
            List<ContractModelCallAudit> calls) {
        if (calls.isEmpty()) return;
        if ((status != ContractReviewStepStatus.FAILED && status != ContractReviewStepStatus.UNKNOWN)
                || current.step.stepType() != ContractReviewStepType.SEMANTIC_AI
                || modelCalls == null
                || current.attempt.externalCallState() != ReviewStepExternalCallState.REQUESTED
                || current.attempt.externalRequestFingerprint() == null) throw conflict();
        var ordinals = new HashSet<Integer>();
        var identities = new HashSet<UUID>();
        if (calls.stream().anyMatch(call -> call.factExtractionAttemptId() != null
                || !current.attempt.attemptId().equals(call.reviewStepAttemptId())
                || current.attempt.attemptNo() != call.attemptNo() || call.scope() != null
                || call.batchStatus() != null
                || !current.attempt.externalRequestFingerprint().equals(call.requestFingerprint())
                || !ordinals.add(call.callOrdinal()) || !identities.add(call.callAuditId()))) throw conflict();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StartedExecution> recover(Candidate candidate, String workerId) {
        if (workerId == null || !workerId.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new IllegalArgumentException("invalid worker identity");
        State current = load(candidate);
        Instant now = clock.instant();
        var disposition = leases.recovery(current.attempt, now);
        if (disposition == ReviewWorkerLeasePolicy.Recovery.NONE) return Optional.empty();
        if (current.step.status() != ContractReviewStepStatus.RUNNING
                || (current.run.status() != ContractReviewRunStatus.RUNNING
                    && current.run.status() != ContractReviewRunStatus.UNKNOWN)) throw conflict();
        CurrentUser actor = businessActor(current.run);
        StartCommand command = command(current);
        var operation = idempotency.findByScopeAndKey(ReviewStepStartTransactionCoordinator.SCOPE,
                command.idempotencyKey()).filter(value -> value.status() == IdempotencyStatus.IN_PROGRESS
                    && value.actorId().equals(current.run.requestedBy())
                    && "CONTRACT_REVIEW_STEP".equals(value.resourceType())
                    && value.resourceId().equals(current.step.stepId().toString())
                    && value.requestFingerprint().equals(ReviewStepStartTransactionCoordinator.fingerprint(
                            actor, current.run, current.step, current.attempt))
                    && "CONTRACT_REVIEW_STEP_ATTEMPT".equals(value.resultResourceType())
                    && current.attempt.attemptId().toString().equals(value.resultResourceId()))
                .orElseThrow(ReviewWorkerTransactionCoordinator::conflict);
        var execution = new StartedExecution(current.run, current.step, current.attempt,
                operation, actor, command, List.of());
        if (disposition == ReviewWorkerLeasePolicy.Recovery.UNKNOWN) {
            Plan plan = compatibleCheckpoint(current).orElse(null);
            if (plan != null) {
                List<Unit> units = semanticCheckpoints.findUnits(plan.planId());
                if (units != null && !units.isEmpty() && units.stream().allMatch(unit -> unit != null
                        && unit.planId().equals(plan.planId()) && unit.status() != UnitStatus.CLAIMED)) {
                    // UNKNOWN dominates a known failure. Only openOrClaim may turn SENT into
                    // UNKNOWN and append its call audit; recovery itself never infers or sends.
                    boolean uncertain = units.stream().anyMatch(unit -> unit.status() == UnitStatus.SENT
                            || unit.status() == UnitStatus.UNKNOWN);
                    Map<String, Unit> latest = new HashMap<>();
                    for (Unit unit : units) latest.merge(unit.logicalKey(), unit,
                            (left, right) -> left.executionNo() >= right.executionNo() ? left : right);
                    if (!uncertain && latest.values().stream().anyMatch(unit -> unit.status() == UnitStatus.FAILED
                            && unit.attemptId().equals(current.attempt.attemptId()))) {
                        closeState(execution, current, ContractReviewStepStatus.FAILED,
                                "WORKER_SEMANTIC_CHECKPOINT_FAILED", LeaseCondition.EXPIRED, now);
                        return Optional.empty();
                    }
                    inputs.load(current.run.projectId(), current.run.contractId(), current.run.runId());
                    var next = nextAttempt(current.attempt, ContractReviewStepStatus.RUNNING, workerId,
                            now.plus(leaseDuration), current.attempt.externalCallState(),
                            current.attempt.externalRequestFingerprint(), null, null);
                    var reclaimed = workers.compareAndSetAttempt(current.attempt, next, LeaseCondition.EXPIRED, now);
                    var recovered = replace(execution, current, reclaimed);
                    audit(recovered, "CHECKPOINT_RECLAIMED", OperationAuditResultStatus.SUCCESS, now);
                    return Optional.of(recovered);
                }
            }
            closeState(execution, current, ContractReviewStepStatus.UNKNOWN,
                    "WORKER_EXTERNAL_OUTCOME_UNPROVEN", LeaseCondition.EXPIRED, now);
            return Optional.empty();
        }
        if (current.step.stepType() == ContractReviewStepType.F02_DETERMINISTIC) {
            var f02 = idempotency.findByScopeAndKey(ContractReviewService.IDEMPOTENCY_SCOPE,
                    FrozenF02ReviewStepExecutor.internalKey(current.run.runId(), current.step.stepId(),
                            current.attempt.attemptId()));
            // A durable F02 START may have escaped even while the outer attempt says NOT_STARTED.
            // Never reinterpret an in-progress/failed/ambiguous inner operation as safe absence.
            if (f02.isPresent()) {
                if (f02.get().status() == IdempotencyStatus.SUCCEEDED) {
                    UUID taskId = UUID.fromString(f02.get().resultResourceId());
                    StepComputationResult recoveredResult;
                    try {
                        // Existing provenance validator proves inner identity, real DONE task, checks and createdBy.
                        var durable = outputs.validateF02Output(current.run, current.step, current.attempt,
                                taskId, current.run.requestedBy());
                        if (f02Mapper != null) {
                            FrozenReviewInput frozen = inputs.load(
                                    current.run.projectId(), current.run.contractId(), current.run.runId());
                            recoveredResult = f02Mapper.map(frozen,
                                    new ReviewExecutionContext(current.run, current.step, current.attempt,
                                            actor, command.metadata(), List.of(), value -> { }),
                                    taskId, durable.checks());
                        } else {
                            recoveredResult = new StepComputationResult("AUDIT_TASK", taskId,
                                    List.of(), List.of(), null, null, null);
                        }
                    } catch (com.fuquan.audit.common.exception.BusinessException invalidPublication) {
                        // The inner task is durably successful, so re-execution would duplicate business truth.
                        // Close for explicit reconciliation instead of retrying the same invalid projection forever.
                        closeState(execution, current, ContractReviewStepStatus.UNKNOWN,
                                "WORKER_F02_PUBLICATION_UNPROVEN", LeaseCondition.EXPIRED, now);
                        return Optional.empty();
                    }
                    var reclaimed = workers.compareAndSetAttempt(current.attempt,
                            nextAttempt(current.attempt, ContractReviewStepStatus.RUNNING, workerId,
                                    now.plus(leaseDuration), ReviewStepExternalCallState.NOT_STARTED, null, null, null),
                            LeaseCondition.EXPIRED, now);
                    var recovered = replace(execution, current, reclaimed);
                    // Recovery and publication share this short transaction; no executor or new F02 task is invoked.
                    completeFenced(recovered, recoveredResult);
                    return Optional.empty();
                }
                closeState(execution, current, ContractReviewStepStatus.UNKNOWN,
                        "WORKER_F02_OUTCOME_REQUIRES_RECONCILIATION", LeaseCondition.EXPIRED, now);
                return Optional.empty();
            }
        }
        inputs.load(current.run.projectId(), current.run.contractId(), current.run.runId());
        var configurations = configurations(current);
        var reclaimed = nextAttempt(current.attempt, ContractReviewStepStatus.RUNNING, workerId,
                now.plus(leaseDuration), ReviewStepExternalCallState.NOT_STARTED, null, null, null);
        reclaimed = workers.compareAndSetAttempt(current.attempt, reclaimed, LeaseCondition.EXPIRED, now);
        var result = new StartedExecution(current.run, current.step, reclaimed, operation, actor, command, configurations);
        audit(result, "RECLAIMED", OperationAuditResultStatus.SUCCESS, now);
        return Optional.of(result);
    }

    /** Trust only one exact durable parent and its immutable, self-consistent frozen plan. */
    private Optional<Plan> compatibleCheckpoint(State current) {
        if (semanticCheckpoints == null || current.run.status() != ContractReviewRunStatus.RUNNING
                || current.step.stepType() != ContractReviewStepType.SEMANTIC_AI
                || !ContractReviewExecutionPolicyResolver.isDurableModelPolicy(current.run.modelPolicyVersion())
                || !current.run.modelPolicyVersion().equals(current.step.stepPolicyVersion())
                || current.attempt.externalCallState() != ReviewStepExternalCallState.REQUESTED
                || current.attempt.externalRequestFingerprint() == null
                || !current.attempt.externalRequestFingerprint().matches("[a-f0-9]{64}")) return Optional.empty();
        Plan plan = semanticCheckpoints.lockPlanByStep(current.step.stepId()).orElse(null);
        if (plan == null || !plan.runId().equals(current.run.runId())
                || !plan.stepId().equals(current.step.stepId())
                || !plan.currentAttemptId().equals(current.attempt.attemptId())) return Optional.empty();
        try {
            JsonNode envelope = CHECKPOINT_JSON.readTree(plan.spec().planJson());
            JsonNode manifest = envelope.path("pythonManifest");
            if (!envelope.isObject() || !manifest.isObject()
                    || !current.run.inputSnapshotIdentity().equals(envelope.path("frozenInputIdentity").asText())
                    || !manifest.path("inputFingerprint").isTextual()
                    || !manifest.path("inputFingerprint").asText().matches("[a-f0-9]{64}")
                    || !plan.spec().inputFingerprint().equals(SemanticCheckpointTypes.digest(
                            current.run.inputSnapshotIdentity() + "\n" + manifest.path("inputFingerprint").asText()))
                    || !plan.spec().policyFingerprint().equals(manifest.path("policyFingerprint").asText())
                    || !manifest.path("policy").isObject()
                    || !ContractReviewExecutionPolicyResolver.moneyModeForPolicy(current.run.modelPolicyVersion())
                            .equals(manifest.path("policy").path("monetaryMode").asText("STRICT"))
                    || ("OBSERVE".equals(ContractReviewExecutionPolicyResolver.moneyModeForPolicy(current.run.modelPolicyVersion()))
                        && !com.fuquan.audit.contract.application.ai.ContractMonetaryObservation.PROTOCOL
                            .equals(manifest.path("policy").path("monetaryObservationProtocol").asText()))
                    || !ContractReviewExecutionPolicyResolver.DURABLE_WORK_POLICY_VERSION
                            .equals(manifest.path("policy").path("version").asText())
                    || !plan.spec().policyFingerprint().equals(SemanticCheckpointTypes.digest(
                            CHECKPOINT_JSON.writeValueAsString(canonicalCheckpointValue(manifest.path("policy")))))) {
                return Optional.empty();
            }
            var identity = ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).deepCopy();
            identity.remove("planFingerprint");
            if (!manifest.path("planFingerprint").asText().equals(SemanticCheckpointTypes.digest(
                    CHECKPOINT_JSON.writeValueAsString(canonicalCheckpointValue(identity))))) return Optional.empty();
            com.fuquan.audit.contract.application.reviewrun.semantic.SemanticWorkManifest.validateStoredSpec(
                    plan.spec(), manifest, ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy(current.run.modelPolicyVersion()));
            return Optional.of(plan);
        } catch (java.io.IOException | IllegalArgumentException invalidPrivateIdentity) {
            return Optional.empty();
        }
    }

    private static Object canonicalCheckpointValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(field -> sorted.put(field.getKey(), canonicalCheckpointValue(field.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalCheckpointValue(value)));
            return values;
        }
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        return null;
    }

    private void closeState(StartedExecution execution, State current, ContractReviewStepStatus status,
            String reason, LeaseCondition condition, Instant now) {
        closeState(execution, current, status, reason, condition, now, List.of());
    }

    private void closeState(StartedExecution execution, State current, ContractReviewStepStatus status,
            String reason, LeaseCondition condition, Instant now, List<ContractModelCallAudit> calls) {
        if (status != ContractReviewStepStatus.FAILED && status != ContractReviewStepStatus.UNKNOWN)
            throw new IllegalArgumentException("invalid closure status");
        if (reason == null || !reason.matches("[A-Z0-9_]{1,100}"))
            throw new IllegalArgumentException("invalid safe reason");
        var external = current.attempt.externalCallState() == ReviewStepExternalCallState.NOT_STARTED
                ? ReviewStepExternalCallState.NOT_STARTED : status == ContractReviewStepStatus.UNKNOWN
                ? ReviewStepExternalCallState.OUTCOME_UNKNOWN : ReviewStepExternalCallState.COMPLETED;
        workers.compareAndSetAttempt(current.attempt, nextAttempt(current.attempt, status,
                current.attempt.leaseOwner(), current.attempt.leaseUntil(), external,
                current.attempt.externalRequestFingerprint(), reason, now), condition, now);
        // The lease CAS holds the row lock before audit insertion; all closure facts commit together.
        if (!calls.isEmpty()) modelCalls.insertAll(calls);
        steps.transition(current.step, step(current.step, status, reason, now));
        var aggregate = aggregator.status(steps.findStepsByRunId(current.run.runId()));
        if (current.run.status() != ContractReviewRunStatus.UNKNOWN || aggregate != ContractReviewRunStatus.UNKNOWN)
            runs.transition(current.run, run(current.run, aggregate,
                    aggregate == ContractReviewRunStatus.UNKNOWN || aggregate == ContractReviewRunStatus.FAILED
                            ? reason : null, now));
        if (status == ContractReviewStepStatus.FAILED) idempotency.markFailed(execution.idempotency(), now);
        audit(execution, status.name(), OperationAuditResultStatus.FAILURE, now);
    }

    private State load(Candidate candidate) {
        var run = runs.findByIdForUpdate(candidate.runId()).orElseThrow(ReviewWorkerTransactionCoordinator::conflict);
        var step = steps.findStepById(candidate.stepId()).orElseThrow(ReviewWorkerTransactionCoordinator::conflict);
        var attempt = attempts.findAttemptById(candidate.attemptId()).orElseThrow(ReviewWorkerTransactionCoordinator::conflict);
        if (!run.runId().equals(step.runId()) || !run.runId().equals(attempt.runId())
                || !step.stepId().equals(attempt.stepId()) || step.currentAttemptNo() != attempt.attemptNo()
                || attempt.versionNo() != candidate.versionNo()) throw conflict();
        return new State(run, step, attempt);
    }
    private State current(StartedExecution execution) {
        var state = load(new Candidate(execution.run().runId(), execution.step().stepId(),
                execution.attempt().attemptId(), execution.attempt().versionNo()));
        if ((state.run.status() != ContractReviewRunStatus.RUNNING && state.run.status() != ContractReviewRunStatus.UNKNOWN)
                || state.step.status() != ContractReviewStepStatus.RUNNING
                || !state.run.requestedBy().equals(execution.actor().userId())
                || !state.attempt.operationIdentity().equals(execution.attempt().operationIdentity())) throw conflict();
        return state;
    }
    private void fence(StartedExecution execution, State state, Instant now) {
        leases.requireFence(state.attempt, execution.attempt().leaseOwner(), execution.attempt().versionNo(), now);
    }
    private CurrentUser businessActor(ContractReviewRun run) {
        var identity = workers.findBusinessInitiator(run.requestedBy())
                .filter(value -> value.userId().equals(run.requestedBy())).orElseThrow(ReviewWorkerTransactionCoordinator::conflict);
        return new CurrentUser(identity.userId(), identity.username(), identity.displayName(), Set.of());
    }
    private List<ContractRuleConfiguration> configurations(State state) {
        if (!state.run.reviewProfileVersion().equals(ContractReviewExecutionPolicyResolver.REVIEW_PROFILE_VERSION)) throw conflict();
        String expected = switch (state.step.stepType()) {
            case F02_DETERMINISTIC -> state.run.ruleVersion();
            case SEMANTIC_AI -> {
                String frozen = state.run.modelPolicyVersion();
                if (!ContractReviewExecutionPolicyResolver.MODEL_POLICY_VERSION.equals(frozen)
                        && !ContractReviewExecutionPolicyResolver.isDurableModelPolicy(frozen)) throw conflict();
                yield frozen;
            }
            case BREACH -> ReviewRunManifestFactory.BREACH_POLICY_VERSION;
            case TERM -> ReviewRunManifestFactory.TERM_POLICY_VERSION;
            case MATERIAL_VALIDATION -> throw conflict();
        };
        if (!expected.equals(state.step.stepPolicyVersion())) throw conflict();
        if (state.step.stepType() != ContractReviewStepType.F02_DETERMINISTIC) return List.of();
        var resolved = policies.resolveCurrentF02();
        if (!state.run.ruleVersion().equals(resolved.ruleVersion())) throw conflict();
        return resolved.enabledF02Configurations();
    }
    private static StartCommand command(State state) {
        String correlation = state.attempt.attemptId().toString();
        return new StartCommand(state.run.projectId(), state.run.contractId(), state.run.runId(), state.step.stepId(),
                "reviewworker:" + state.attempt.operationIdentity(), new ProjectRequestMetadata(correlation, correlation, null));
    }
    private static StartedExecution replace(StartedExecution old, State state, ContractReviewStepAttempt next) {
        return new StartedExecution(state.run, state.step, next, old.idempotency(), old.actor(), old.command(), old.f02Configurations());
    }
    private static ContractReviewStepAttempt nextAttempt(ContractReviewStepAttempt a, ContractReviewStepStatus status,
            String owner, Instant until, ReviewStepExternalCallState external, String fingerprint, String reason, Instant completed) {
        return new ContractReviewStepAttempt(a.attemptId(), a.runId(), a.stepId(), a.attemptNo(), a.operationIdentity(),
                status, owner, until, a.claimedAt(), external, fingerprint, a.providerRequestId(), a.provider(), a.model(),
                a.startedAt(), completed, reason, null, null, a.versionNo() + 1);
    }
    private static ContractReviewStep step(ContractReviewStep s, ContractReviewStepStatus status, String reason, Instant now) {
        return new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), s.required(), status, s.stepPolicyVersion(),
                s.currentAttemptNo(), null, null, reason, s.startedAt() == null ? now : s.startedAt(),
                status == ContractReviewStepStatus.RUNNING ? null : now, s.versionNo() + 1);
    }
    private static ContractReviewRun run(ContractReviewRun r, ContractReviewRunStatus status, String reason, Instant now) {
        return new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), r.contractVersion(), r.sourceCandidateId(),
                r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(), r.contractSnapshotSha256(),
                r.reviewProfileVersion(), r.ruleVersion(), r.modelPolicyVersion(), r.inputSnapshotIdentity(), r.requestedBy(),
                status, r.requestedAt(), r.startedAt() == null ? now : r.startedAt(),
                status == ContractReviewRunStatus.COMPLETED ? now : null,
                status == ContractReviewRunStatus.FAILED ? now : null,
                status == ContractReviewRunStatus.UNKNOWN ? now : null, reason, r.versionNo() + 1);
    }
    private void audit(StartedExecution execution, String action, OperationAuditResultStatus result, Instant now) {
        audit.append(new OperationAuditEvent("CONTRACT_REVIEW_WORKER_" + action, "CONTRACT", action,
                execution.actor().userId(), execution.actor().username(), "CONTRACT_REVIEW_STEP_ATTEMPT",
                execution.attempt().attemptId().toString(), null, result, action, "contract review worker " + action,
                "{\"workerId\":\"" + execution.attempt().leaseOwner() + "\",\"runId\":\"" + execution.run().runId() + "\"}",
                execution.command().metadata().requestId(), execution.command().metadata().traceId(), null, now));
    }
    private static com.fuquan.audit.common.exception.BusinessException conflict() { return ReviewWorkerLeasePolicy.conflict(); }
    private record State(ContractReviewRun run, ContractReviewStep step, ContractReviewStepAttempt attempt) { }
}
