package com.fuquan.audit.contract.infra.memory.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepTransitionGuard;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Local-profile aggregate store. Publication is fenced by the create coordinator; DM8 uses transaction rollback. */
@Repository
@Profile("local")
public class InMemoryContractReviewRunRepositories implements ContractReviewRunRepository,
        ContractReviewRunMaterialRepository, ContractReviewStepRepository, ContractReviewStepAttemptRepository {
    private final ConcurrentMap<UUID, ContractReviewRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractReviewRunMaterial> materials = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractReviewStep> steps = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractReviewStepAttempt> attempts = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ContractReviewRun run) {
        if (runs.putIfAbsent(run.runId(), run) != null) throw conflict();
    }
    @Override
    public synchronized void insert(ContractReviewRunMaterial material) {
        requireRun(material.runId());
        if (!runs.get(material.runId()).projectId().equals(material.projectId())
                || materials.containsKey(material.runMaterialId())
                || findMaterialsByRunId(material.runId()).stream().anyMatch(value ->
                value.fileId().equals(material.fileId()) || value.ordinal() == material.ordinal())) throw conflict();
        materials.put(material.runMaterialId(), material);
    }
    @Override
    public synchronized void insert(ContractReviewStep step) {
        requireRun(step.runId());
        if (steps.containsKey(step.stepId()) || findStepsByRunId(step.runId()).stream()
                .anyMatch(value -> value.stepType() == step.stepType())) throw conflict();
        steps.put(step.stepId(), step);
    }
    @Override
    public synchronized void insert(ContractReviewStepAttempt attempt) {
        var step = steps.get(attempt.stepId());
        if (step == null || !step.runId().equals(attempt.runId()) || attempts.containsKey(attempt.attemptId())
                || attempts.values().stream().anyMatch(value ->
                value.operationIdentity().equals(attempt.operationIdentity())
                        || value.stepId().equals(attempt.stepId()) && value.attemptNo() == attempt.attemptNo()))
            throw conflict();
        attempts.put(attempt.attemptId(), attempt);
    }
    @Override
    public Optional<ContractReviewRun> findById(UUID runId) { return Optional.ofNullable(runs.get(runId)); }
    @Override
    public Optional<ContractReviewRun> findLatestByProjectIdAndContractIdAndRequestedBy(
            UUID projectId, UUID contractId, String requestedBy) {
        return runs.values().stream()
                .filter(run -> run.projectId().equals(projectId)
                        && run.contractId().equals(contractId)
                        && run.requestedBy().equals(requestedBy))
                .max(Comparator.comparing(ContractReviewRun::requestedAt)
                        .thenComparing(run -> run.runId().toString()));
    }
    @Override
    public synchronized Optional<ContractReviewRun> findByIdForUpdate(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }
    @Override
    public synchronized ContractReviewRun transition(ContractReviewRun expected, ContractReviewRun next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        var current = runs.get(expected.runId());
        if (current == null || current.status() != expected.status()
                || current.versionNo() != expected.versionNo()) throw conflict();
        runs.put(expected.runId(), next);
        return next;
    }
    @Override
    public Optional<ContractReviewStep> findStepById(UUID stepId) {
        return Optional.ofNullable(steps.get(stepId));
    }
    @Override
    public synchronized ContractReviewStep transition(ContractReviewStep expected, ContractReviewStep next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        var current = steps.get(expected.stepId());
        if (current == null || current.status() != expected.status()
                || current.versionNo() != expected.versionNo()) throw conflict();
        steps.put(expected.stepId(), next);
        return next;
    }
    @Override
    public Optional<ContractReviewStepAttempt> findAttemptById(UUID attemptId) {
        return Optional.ofNullable(attempts.get(attemptId));
    }
    @Override
    public synchronized ContractReviewStepAttempt transition(ContractReviewStepAttempt expected,
            ContractReviewStepAttempt next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        var current = attempts.get(expected.attemptId());
        if (current == null || current.status() != expected.status()
                || current.versionNo() != expected.versionNo()) throw conflict();
        attempts.put(expected.attemptId(), next);
        return next;
    }
    @Override
    public List<ContractReviewRunMaterial> findMaterialsByRunId(UUID runId) {
        return materials.values().stream().filter(v -> v.runId().equals(runId))
                .sorted(Comparator.comparingInt(ContractReviewRunMaterial::ordinal)).toList();
    }
    @Override
    public List<ContractReviewStep> findStepsByRunId(UUID runId) {
        return steps.values().stream().filter(v -> v.runId().equals(runId))
                .sorted(Comparator.comparingInt(v -> v.stepType().ordinal())).toList();
    }
    @Override
    public List<ContractReviewStep> findByOutputResource(String resourceType, UUID resourceId) {
        return steps.values().stream().filter(value ->
                resourceType.equals(value.outputResourceType())
                        && resourceId.equals(value.outputResourceId()))
                .sorted(Comparator.comparing(value -> value.stepId().toString())).toList();
    }
    @Override
    public List<ContractReviewStepAttempt> findAttemptsByRunId(UUID runId) {
        return attempts.values().stream().filter(v -> v.runId().equals(runId))
                .sorted(Comparator.comparing((ContractReviewStepAttempt v) ->
                        steps.get(v.stepId()).stepType().ordinal()).thenComparingInt(ContractReviewStepAttempt::attemptNo))
                .toList();
    }

    /** One coordinator calls this only after its own acquired claim fails before success. */
    @Override
    public synchronized void rollbackUncommittedRun(UUID runId) {
        attempts.values().removeIf(value -> value.runId().equals(runId));
        steps.values().removeIf(value -> value.runId().equals(runId));
        materials.values().removeIf(value -> value.runId().equals(runId));
        runs.remove(runId);
    }

    private void requireRun(UUID runId) { if (!runs.containsKey(runId)) throw conflict(); }
    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "review run manifest conflict");
    }
}
