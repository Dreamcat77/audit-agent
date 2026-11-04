package com.fuquan.audit.integration.dm8;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8SemanticCheckpointRepository implements SemanticCheckpointRepository {
    private final SemanticCheckpointMapper mapper;
    private final ObjectMapper json = new ObjectMapper();
    public Dm8SemanticCheckpointRepository(SemanticCheckpointMapper mapper) { this.mapper = mapper; }
    public Optional<Plan> findPlanByStep(UUID stepId) { return Optional.ofNullable(mapper.findPlanByStep(stepId.toString())).map(this::plan); }
    public Optional<Plan> lockPlanByStep(UUID stepId) { return Optional.ofNullable(mapper.lockPlanByStep(stepId.toString())).map(this::plan); }
    public void lockParentAttempt(UUID attemptId) { if (mapper.lockParentAttempt(attemptId.toString()) == null) throw conflict(); }
    public List<Unit> findUnits(UUID planId) { return mapper.findUnits(planId.toString()).stream().map(this::unit).toList(); }
    public void insertPlan(Plan plan) { if (mapper.insertPlan(row(plan)) != 1) throw conflict(); }
    public void insertUnit(Unit unit) { if (mapper.insertUnit(row(unit)) != 1) throw conflict(); }
    public void compareAndSetPlan(Plan expected, Plan next) {
        if (!expected.planId().equals(next.planId()) || next.versionNo() != expected.versionNo() + 1
                || mapper.updatePlanCas(row(expected), row(next)) != 1) throw conflict();
    }
    public void compareAndSetUnit(Unit expected, Unit next) {
        if (!expected.executionId().equals(next.executionId()) || next.versionNo() != expected.versionNo() + 1
                || mapper.updateUnitCas(row(expected), row(next)) != 1) throw conflict();
    }
    private static SemanticCheckpointPlanRow row(Plan p) {
        return new SemanticCheckpointPlanRow(p.planId().toString(), p.runId().toString(), p.stepId().toString(),
                p.originalAttemptId().toString(), p.currentAttemptId().toString(), p.spec().inputFingerprint(),
                p.spec().policyFingerprint(), p.spec().planJson(), time(p.spec().deadlineAt()), p.spec().timeLimitMs(),
                p.spec().callLimit(), p.spec().repairLimit(), p.owner(), p.fence(), time(p.leaseUntil()), p.status().name(),
                p.usedCalls(), p.reservedCalls(), p.usedRepairs(), p.reservedRepairs(), p.usedMs(), p.reservedMs(),
                p.finalResultSha256(), p.versionNo(), time(p.createdAt()), time(p.updatedAt()));
    }
    private Plan plan(SemanticCheckpointPlanRow p) {
        return new Plan(id(p.planId()), id(p.runId()), id(p.stepId()), id(p.originalAttemptId()), id(p.currentAttemptId()),
                new PlanSpec(p.inputFingerprint(), p.policyFingerprint(), p.planJson(), instant(p.deadlineAt()),
                        p.timeLimitMs(), p.callLimit(), p.repairLimit()), p.owner(), p.fence(), instant(p.leaseUntil()),
                PlanStatus.valueOf(p.status()), p.usedCalls(), p.reservedCalls(), p.usedRepairs(), p.reservedRepairs(),
                p.usedMs(), p.reservedMs(), p.finalResultSha256(), p.versionNo(), instant(p.createdAt()), instant(p.updatedAt()));
    }
    private SemanticCheckpointUnitRow row(Unit u) {
        String deps;
        try { deps = json.writeValueAsString(u.dependencies()); }
        catch (java.io.IOException error) { throw new IllegalStateException("checkpoint dependencies invalid"); }
        return new SemanticCheckpointUnitRow(u.executionId().toString(), u.planId().toString(), u.logicalKey(),
                u.executionNo(), u.ordinal(), u.purpose().name(), u.attemptId().toString(), u.inputFingerprint(), deps,
                u.status().name(), u.owner(), u.fence(), time(u.leaseUntil()), u.reservedMs(), u.durationMs(),
                u.resultJson(), u.resultSha256(), u.safeReason(), u.call() == null ? null : u.call().callAuditId().toString(),
                u.call() == null ? null : u.call().provider(), u.call() == null ? null : u.call().model(),
                u.call() == null ? null : u.call().requestFingerprint(), u.callOrdinal(), u.versionNo(),
                time(u.createdAt()), time(u.updatedAt()));
    }
    private Unit unit(SemanticCheckpointUnitRow u) {
        List<String> deps;
        try { deps = json.readValue(u.dependenciesJson(), new TypeReference<List<String>>() { }); }
        catch (java.io.IOException error) { throw new IllegalStateException("checkpoint dependencies unreadable"); }
        return new Unit(id(u.unitExecutionId()), id(u.planId()), u.logicalUnitKey(), u.executionNo(), u.unitOrdinal(),
                Purpose.valueOf(u.purpose()), id(u.attemptId()), u.inputFingerprint(), deps, UnitStatus.valueOf(u.status()),
                u.owner(), u.fence(), instant(u.leaseUntil()), u.reservedMs(), u.durationMs(), u.resultJson(),
                u.resultSha256(), u.safeReason(), u.callAuditId() == null ? null : new CallMetadata(id(u.callAuditId()),
                        u.provider(), u.modelName(), u.requestFingerprint()), u.callOrdinal(), u.versionNo(),
                instant(u.createdAt()), instant(u.updatedAt()));
    }
    private static UUID id(String value) { return UUID.fromString(value); }
    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static IllegalStateException conflict() { return new IllegalStateException("semantic checkpoint CAS conflict"); }
}
