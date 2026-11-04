package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short DM8 transactions only. HTTP and final business publication belong to the caller. */
public class SemanticCheckpointCoordinator {
    private final SemanticCheckpointRepository repository;
    private final ContractReviewRunRepository runs;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final ContractModelCallAuditRepository audit;
    private final Clock clock;
    public SemanticCheckpointCoordinator(SemanticCheckpointRepository repository,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, ContractModelCallAuditRepository audit, Clock clock) {
        this.repository = Objects.requireNonNull(repository); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.attempts = Objects.requireNonNull(attempts);
        this.audit = Objects.requireNonNull(audit); this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Plan openOrClaim(Parent parent, PlanSpec spec, List<UnitSpec> units) {
        lockRun(parent);
        Plan existing = repository.lockPlanByStep(parent.stepId()).orElse(null);
        ContractReviewStepAttempt actual = parent(parent);
        Instant now = clock.instant();
        validateSpecs(spec, units);
        if (existing == null) {
            if (!spec.deadlineAt().isAfter(now)
                    || spec.deadlineAt().isAfter(now.plusMillis(spec.timeLimitMs()))) throw conflict();
            Plan plan = new Plan(UUID.randomUUID(), parent.runId(), parent.stepId(), parent.attemptId(),
                    parent.attemptId(), spec, parent.leaseOwner(), 1, actual.leaseUntil(), PlanStatus.ACTIVE,
                    0, 0, 0, 0, 0, 0, null, 0, now, now);
            repository.insertPlan(plan);
            for (UnitSpec unit : units) repository.insertUnit(new Unit(UUID.randomUUID(), plan.planId(),
                    unit.logicalKey(), 1, unit.ordinal(), unit.purpose(), parent.attemptId(),
                    unit.inputFingerprint(), unit.dependencies(), UnitStatus.PLANNED, parent.leaseOwner(),
                    plan.fence(), actual.leaseUntil(), 0, 0, null, null, null, null, 0, 0, now, now));
            return plan;
        }
        if (!existing.runId().equals(parent.runId()) || !existing.spec().equals(spec)) throw conflict();
        List<Unit> stored = repository.findUnits(existing.planId());
        var initial = stored.stream().filter(u -> u.executionNo() == 1)
                .collect(Collectors.toMap(Unit::logicalKey, Function.identity()));
        if (initial.size() != units.size() || units.stream().anyMatch(s -> !sameSpec(s, initial.get(s.logicalKey()))))
            throw conflict();
        // A lost process cannot prove whether SENT reached the provider. Commit
        // UNKNOWN and retain all reservation amounts rather than throwing them away.
        for (Unit unit : stored) if (unit.status() == UnitStatus.SENT) unknown(unit, "TRANSPORT_OUTCOME_UNKNOWN", now);
        if (stored.stream().anyMatch(u -> u.status() == UnitStatus.SENT || u.status() == UnitStatus.UNKNOWN))
            return existing;
        // COMPLETE checkpoints still need fenced ownership transfer when the
        // process died before the existing Step publication transaction committed.
        Plan next = existing.change(parent.attemptId(), parent.leaseOwner(), Math.addExact(existing.fence(), 1),
                actual.leaseUntil(), existing.status(), existing.usedCalls(), existing.reservedCalls(),
                existing.usedRepairs(), existing.reservedRepairs(), existing.usedMs(), existing.reservedMs(),
                existing.finalResultSha256(), now);
        repository.compareAndSetPlan(existing, next);
        return next;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // fingerprint is the immutable planned input/dependency-manifest identity.
    // The actual dynamic synthesis request hash belongs in CallMetadata instead.
    public Unit reserveAndMarkSent(Access access, String key, Purpose purpose, String fingerprint,
            long reservedMs, CallMetadata call) {
        hash(key); hash(fingerprint); Objects.requireNonNull(call); Objects.requireNonNull(purpose);
        Plan plan = access(access); Instant now = clock.instant();
        if (plan.status() != PlanStatus.ACTIVE || reservedMs < 1 || !plan.spec().deadlineAt().isAfter(now)
                || reservedMs > Duration.between(now, plan.spec().deadlineAt()).toMillis()
                || plan.usedCalls() + plan.reservedCalls() >= plan.spec().callLimit()
                || Math.addExact(Math.addExact(plan.usedMs(), plan.reservedMs()), reservedMs) > plan.spec().timeLimitMs()
                || (purpose == Purpose.REPAIR && plan.usedRepairs() + plan.reservedRepairs() >= plan.spec().repairLimit()))
            throw conflict();
        List<Unit> all = repository.findUnits(plan.planId());
        if (all.stream().anyMatch(u -> u.status() == UnitStatus.SENT || u.status() == UnitStatus.UNKNOWN)) throw conflict();
        Map<String, Unit> latest = latest(all);
        Unit prior = Optional.ofNullable(latest.get(key)).orElseThrow(SemanticCheckpointCoordinator::conflict);
        if (prior.dependencies().stream().anyMatch(dependency -> latest.get(dependency) == null
                || latest.get(dependency).status() != UnitStatus.VERIFIED)) throw conflict();
        boolean repair = purpose == Purpose.REPAIR;
        if (repair ? prior.status() != UnitStatus.REPAIR_REQUIRED && !explicitFailedRetry(prior, access)
                : prior.status() != UnitStatus.PLANNED || purpose != prior.purpose()
                    || !prior.inputFingerprint().equals(fingerprint)) throw conflict();
        int ordinal = audit.findByReviewStepAttempt(access.parent().attemptId()).stream()
                .mapToInt(ContractModelCallAudit::callOrdinal).max().orElse(0) + 1;
        Unit sent = new Unit(repair ? UUID.randomUUID() : prior.executionId(), plan.planId(), key,
                repair ? prior.executionNo() + 1 : prior.executionNo(), prior.ordinal(), purpose,
                access.parent().attemptId(), fingerprint, prior.dependencies(), UnitStatus.SENT,
                plan.owner(), plan.fence(), plan.leaseUntil(), reservedMs, 0, null, null, null, call,
                ordinal, repair ? 0 : prior.versionNo() + 1, repair ? now : prior.createdAt(), now);
        Plan reserved = plan.change(plan.currentAttemptId(), plan.owner(), plan.fence(), plan.leaseUntil(),
                plan.status(), plan.usedCalls(), plan.reservedCalls() + 1, plan.usedRepairs(),
                plan.reservedRepairs() + (repair ? 1 : 0), plan.usedMs(), Math.addExact(plan.reservedMs(), reservedMs), null, now);
        repository.compareAndSetPlan(plan, reserved);
        if (repair) repository.insertUnit(sent); else repository.compareAndSetUnit(prior, sent);
        return sent;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Unit completeUnit(Access access, UUID executionId, UnitStatus terminal, String resultJson,
            String resultSha256, long durationMs, CallUsage usage, String safeReason) {
        if (!Set.of(UnitStatus.VERIFIED, UnitStatus.REPAIR_REQUIRED, UnitStatus.FAILED).contains(terminal)
                || durationMs < 0) throw new IllegalArgumentException("invalid terminal checkpoint");
        Objects.requireNonNull(usage); reason(safeReason);
        if (terminal == UnitStatus.VERIFIED || resultJson != null) {
            json(resultJson); hash(resultSha256);
            if (!digest(resultJson).equals(resultSha256)) throw new IllegalArgumentException("result digest mismatch");
        } else if (resultSha256 != null) throw new IllegalArgumentException("hash without result");
        Plan plan = access(access); Unit unit = unit(plan, executionId); fence(unit, access);
        if (unit.status() != UnitStatus.SENT) {
            if (unit.status() == terminal && Objects.equals(unit.resultSha256(), resultSha256)
                    && Objects.equals(unit.resultJson(), resultJson) && unit.durationMs() == durationMs
                    && Objects.equals(unit.safeReason(), safeReason) && sameAudit(unit, usage)) return unit;
            throw conflict();
        }
        Instant now = clock.instant();
        Unit next = unit.finish(terminal, durationMs, resultJson, resultSha256, safeReason, now);
        int repair = unit.purpose() == Purpose.REPAIR ? 1 : 0;
        Plan consumed = plan.change(plan.currentAttemptId(), plan.owner(), plan.fence(), plan.leaseUntil(),
                plan.status(), plan.usedCalls() + 1, plan.reservedCalls() - 1,
                plan.usedRepairs() + repair, plan.reservedRepairs() - repair,
                Math.addExact(plan.usedMs(), durationMs), plan.reservedMs() - unit.reservedMs(), null, now);
        repository.compareAndSetPlan(plan, consumed);
        repository.compareAndSetUnit(unit, next);
        insertAudit(next, usage, terminal == UnitStatus.VERIFIED ? CallOutcome.SUCCEEDED : CallOutcome.FAILED, now);
        return next;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    /** Observed UNKNOWN telemetry is audit-only; outstanding reservations remain held. */
    public Unit markUnknown(Access access, UUID executionId, String safeReason, long observedDurationMs,
            CallUsage observedUsage) {
        reason(safeReason); if (safeReason == null || observedDurationMs < 0)
            throw new IllegalArgumentException("unknown requires safe reason and nonnegative duration");
        Objects.requireNonNull(observedUsage);
        Plan plan = access(access); Unit unit = unit(plan, executionId); fence(unit, access);
        if (unit.status() == UnitStatus.UNKNOWN) {
            if (unit.durationMs() == observedDurationMs && Objects.equals(unit.safeReason(), safeReason)
                    && sameAudit(unit, observedUsage)) return unit;
            throw conflict();
        }
        if (unit.status() != UnitStatus.SENT) throw conflict();
        return unknown(unit, safeReason, observedDurationMs, observedUsage, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Unit markUnknown(Access access, UUID executionId, String safeReason) {
        return markUnknown(access, executionId, safeReason, 0, new CallUsage(null, null, null, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    /** finalResultSha256 is the final private synthesis checkpoint JSON digest. */
    public Plan markComplete(Access access, String finalResultSha256) {
        hash(finalResultSha256); Plan plan = access(access);
        var stored = repository.findUnits(plan.planId());
        final List<UnitSpec> initial; final String finalKey;
        try {
            initial = stored.stream().filter(u -> u.executionNo() == 1).sorted(Comparator.comparingInt(Unit::ordinal))
                    .map(u -> new UnitSpec(u.logicalKey(), u.ordinal(), u.purpose(), u.inputFingerprint(), u.dependencies())).toList();
            validateSpecs(plan.spec(), initial);
            finalKey = SemanticWorkManifest.finalLogicalKey(plan.spec(), initial);
        } catch (IllegalArgumentException invalidStoredGraph) { throw conflict(); }
        Map<String, UnitSpec> required = initial.stream().collect(Collectors.toMap(UnitSpec::logicalKey, Function.identity()));
        var latest = latest(stored);
        if (!latest.keySet().equals(required.keySet())) throw conflict();
        Set<String> executions = new HashSet<>();
        for (var unit : stored) {
            var expected = required.get(unit.logicalKey());
            if (!unit.planId().equals(plan.planId()) || expected == null
                    || !executions.add(unit.logicalKey() + ":" + unit.executionNo())
                    || unit.ordinal() != expected.ordinal() || !unit.dependencies().equals(expected.dependencies())
                    || (unit.executionNo() == 1 ? unit.purpose() != expected.purpose()
                        || !unit.inputFingerprint().equals(expected.inputFingerprint()) : unit.purpose() != Purpose.REPAIR)) throw conflict();
        }
        if (plan.usedMs() > plan.spec().timeLimitMs()
                || latest.isEmpty() || latest.values().stream().anyMatch(u -> u.status() != UnitStatus.VERIFIED)
                || latest.values().stream().filter(u -> u.logicalKey().equals(finalKey))
                    .filter(u -> finalResultSha256.equals(u.resultSha256())).count() != 1
                || plan.reservedCalls() != 0 || plan.reservedRepairs() != 0 || plan.reservedMs() != 0) throw conflict();
        if (plan.status() == PlanStatus.COMPLETE) {
            if (!finalResultSha256.equals(plan.finalResultSha256())) throw conflict();
            return plan;
        }
        if (!plan.spec().deadlineAt().isAfter(clock.instant())) throw conflict();
        Plan complete = plan.change(plan.currentAttemptId(), plan.owner(), plan.fence(), plan.leaseUntil(),
                PlanStatus.COMPLETE, plan.usedCalls(), 0, plan.usedRepairs(), 0, plan.usedMs(), 0,
                finalResultSha256, clock.instant());
        repository.compareAndSetPlan(plan, complete); return complete;
    }
    public Optional<Plan> findPlan(UUID stepId) { return repository.findPlanByStep(stepId); }
    public List<Unit> findUnits(UUID planId) { return List.copyOf(repository.findUnits(planId)); }

    private void lockRun(Parent parent) {
        var run = runs.findByIdForUpdate(parent.runId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        if (!run.runId().equals(parent.runId()) || run.status() != ContractReviewRunStatus.RUNNING) throw conflict();
    }
    private ContractReviewStepAttempt parent(Parent parent) {
        repository.lockParentAttempt(parent.attemptId());
        var step = steps.findStepById(parent.stepId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        var attempt = attempts.findAttemptById(parent.attemptId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        if (!step.runId().equals(parent.runId()) || !step.stepId().equals(parent.stepId())
                || step.stepType() != ContractReviewStepType.SEMANTIC_AI || step.status() != ContractReviewStepStatus.RUNNING
                || !attempt.runId().equals(parent.runId()) || !attempt.stepId().equals(parent.stepId())
                || !attempt.attemptId().equals(parent.attemptId()) || attempt.attemptNo() != step.currentAttemptNo()
                || attempt.status() != ContractReviewStepStatus.RUNNING
                || !parent.leaseOwner().equals(attempt.leaseOwner()) || attempt.leaseUntil() == null
                || !attempt.leaseUntil().isAfter(clock.instant())) throw conflict();
        return attempt;
    }
    private Plan access(Access access) {
        lockRun(access.parent());
        Plan plan = repository.lockPlanByStep(access.parent().stepId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        ContractReviewStepAttempt actual = parent(access.parent());
        if (!plan.planId().equals(access.planId()) || plan.fence() != access.fence()
                || !plan.currentAttemptId().equals(access.parent().attemptId())
                || !plan.owner().equals(access.parent().leaseOwner())) throw conflict();
        // Parent heartbeat is authoritative. Only this exact current owner and
        // fence can extend its plan lease; takeover still requires openOrClaim.
        if (actual.leaseUntil().isAfter(plan.leaseUntil())) {
            Plan renewed = plan.change(plan.currentAttemptId(), plan.owner(), plan.fence(), actual.leaseUntil(),
                    plan.status(), plan.usedCalls(), plan.reservedCalls(), plan.usedRepairs(), plan.reservedRepairs(),
                    plan.usedMs(), plan.reservedMs(), plan.finalResultSha256(), clock.instant());
            repository.compareAndSetPlan(plan, renewed); plan = renewed;
        }
        if (!plan.leaseUntil().isAfter(clock.instant())) throw conflict();
        return plan;
    }
    private static void fence(Unit unit, Access access) {
        if (unit.fence() != access.fence() || !unit.owner().equals(access.parent().leaseOwner())
                || !unit.attemptId().equals(access.parent().attemptId())) throw conflict();
    }
    private Unit unit(Plan plan, UUID id) {
        return repository.findUnits(plan.planId()).stream().filter(u -> u.executionId().equals(id))
                .findFirst().orElseThrow(SemanticCheckpointCoordinator::conflict);
    }
    private Unit unknown(Unit unit, String safeReason, Instant now) {
        return unknown(unit, safeReason, 0, new CallUsage(null, null, null, null), now);
    }
    private Unit unknown(Unit unit, String safeReason, long durationMs, CallUsage usage, Instant now) {
        Unit unknown = unit.finish(UnitStatus.UNKNOWN, durationMs, null, null, safeReason, now);
        repository.compareAndSetUnit(unit, unknown);
        insertAudit(unknown, usage, CallOutcome.FAILED, now);
        return unknown;
    }
    private void insertAudit(Unit unit, CallUsage usage, CallOutcome outcome, Instant now) {
        var attempt = attempts.findAttemptById(unit.attemptId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        audit.insertAll(List.of(new ContractModelCallAudit(unit.call().callAuditId(), null, unit.attemptId(),
                attempt.attemptNo(), null, unit.callOrdinal(), unit.purpose() == Purpose.REPAIR ? CallPhase.REPAIR : CallPhase.TRANSPORT,
                unit.call().requestFingerprint(), unit.call().provider(), unit.call().model(), outcome,
                unit.safeReason(), unit.durationMs(), usage.finishReason(), usage.promptTokens(),
                usage.completionTokens(), usage.totalTokens(), null, now)));
    }
    private boolean sameAudit(Unit unit, CallUsage usage) {
        var calls = audit.findByReviewStepAttempt(unit.attemptId()).stream()
                .filter(a -> a.callAuditId().equals(unit.call().callAuditId())).toList();
        if (calls.size() != 1) return false;
        var call = calls.get(0);
        return call.reviewStepAttemptId().equals(unit.attemptId())
                && call.callOrdinal() == unit.callOrdinal()
                && call.requestFingerprint().equals(unit.call().requestFingerprint())
                && call.provider().equals(unit.call().provider()) && call.modelName().equals(unit.call().model())
                && call.phase() == (unit.purpose() == Purpose.REPAIR ? CallPhase.REPAIR : CallPhase.TRANSPORT)
                && call.outcome() == (unit.status() == UnitStatus.VERIFIED ? CallOutcome.SUCCEEDED : CallOutcome.FAILED)
                && call.durationMs() == unit.durationMs() && Objects.equals(call.safeReason(), unit.safeReason())
                && Objects.equals(call.finishReason(), usage.finishReason())
                && Objects.equals(call.promptTokens(), usage.promptTokens())
                && Objects.equals(call.completionTokens(), usage.completionTokens())
                && Objects.equals(call.totalTokens(), usage.totalTokens());
    }
    private boolean explicitFailedRetry(Unit prior, Access access) {
        if (prior.status() != UnitStatus.FAILED || prior.attemptId().equals(access.parent().attemptId())) return false;
        var failed = attempts.findAttemptById(prior.attemptId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        var current = attempts.findAttemptById(access.parent().attemptId()).orElseThrow(SemanticCheckpointCoordinator::conflict);
        return failed.status() == ContractReviewStepStatus.FAILED && failed.attemptNo() < current.attemptNo()
                && failed.runId().equals(access.parent().runId()) && failed.stepId().equals(access.parent().stepId());
    }
    private static Map<String, Unit> latest(List<Unit> units) {
        return units.stream().collect(Collectors.toMap(Unit::logicalKey, Function.identity(),
                (a, b) -> a.executionNo() > b.executionNo() ? a : b));
    }
    private static boolean sameSpec(UnitSpec spec, Unit unit) {
        return unit != null && spec.ordinal() == unit.ordinal() && spec.purpose() == unit.purpose()
                && spec.inputFingerprint().equals(unit.inputFingerprint()) && spec.dependencies().equals(unit.dependencies());
    }
    private static void validateSpecs(PlanSpec spec, List<UnitSpec> units) {
        if (units == null || units.isEmpty() || units.size() > 128) throw new IllegalArgumentException("invalid semantic plan units");
        var keys = new HashSet<String>(); var ordinals = new HashSet<Integer>();
        for (UnitSpec unit : units.stream().sorted(Comparator.comparingInt(UnitSpec::ordinal)).toList()) {
            if (!keys.containsAll(unit.dependencies()) || !keys.add(unit.logicalKey()) || !ordinals.add(unit.ordinal()))
                throw new IllegalArgumentException("invalid semantic dependency order");
        }
        if (SemanticWorkManifest.validateHierarchySpecs(spec, units)) return;
        var finalUnits = units.stream().filter(u -> u.purpose() == Purpose.SYNTHESIZE).toList();
        if (finalUnits.size() != 1 || !new HashSet<>(finalUnits.get(0).dependencies()).equals(
                units.stream().filter(u -> u.purpose() != Purpose.SYNTHESIZE).map(UnitSpec::logicalKey).collect(Collectors.toSet())))
            throw new IllegalArgumentException("one final synthesis must depend on every analysis unit");
    }
    private static IllegalStateException conflict() { return new IllegalStateException("semantic checkpoint ownership, identity or budget conflict"); }
}
