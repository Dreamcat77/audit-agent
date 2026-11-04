package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepTransitionGuard;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** DM8/MyBatis adapter; business transaction/rollback is owned by the command coordinator. */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractReviewRunRepositories implements ContractReviewRunRepository,
        ContractReviewRunMaterialRepository, ContractReviewStepRepository, ContractReviewStepAttemptRepository {
    private final ContractReviewRunMapper mapper;
    public Dm8ContractReviewRunRepositories(ContractReviewRunMapper mapper) { this.mapper = mapper; }

    @Override public void rollbackUncommittedRun(UUID runId) {
        // The surrounding DM8 write transaction rolls back all four manifest tables together.
    }

    @Override public void insert(ContractReviewRun run) {
        requireOne(mapper.insertRun(new ContractReviewRunPersistenceRow(
                id(run.runId()), id(run.projectId()), id(run.contractId()), run.contractVersion(),
                id(run.sourceCandidateId()), id(run.sourceConfirmationId()), run.runSchemaVersion(),
                run.contractSnapshotCanonicalJson(), run.contractSnapshotSha256(), run.reviewProfileVersion(),
                run.ruleVersion(), run.modelPolicyVersion(), run.inputSnapshotIdentity(), run.requestedBy(),
                run.status().name(), date(run.requestedAt()), date(run.startedAt()), date(run.completedAt()),
                date(run.failedAt()), date(run.unknownAt()), run.safeReasonCode(), run.versionNo())));
    }
    @Override public void insert(ContractReviewRunMaterial material) {
        requireOne(mapper.insertMaterial(new ContractReviewRunMaterialPersistenceRow(
                id(material.runMaterialId()), id(material.runId()), material.ordinal(), id(material.projectId()),
                id(material.fileId()), material.fileSha256(), id(material.parseResultId()),
                material.parseVersion(), material.canonicalTextSha256(), material.effectiveRole().name(),
                id(material.roleDecisionId()), id(material.identityDecisionId()),
                id(material.sourceRoleDispositionId()), material.selectedBy(), date(material.snapshottedAt()))));
    }
    @Override public void insert(ContractReviewStep step) {
        requireOne(mapper.insertStep(new ContractReviewStepPersistenceRow(
                id(step.stepId()), id(step.runId()), step.stepType().name(), step.required() ? 1 : 0,
                step.status().name(), step.stepPolicyVersion(), step.currentAttemptNo(),
                step.outputResourceType(), id(step.outputResourceId()), step.safeReasonCode(),
                date(step.startedAt()), date(step.completedAt()), step.versionNo())));
    }
    @Override public void insert(ContractReviewStepAttempt attempt) {
        requireOne(mapper.insertAttempt(new ContractReviewStepAttemptPersistenceRow(
                id(attempt.attemptId()), id(attempt.runId()), id(attempt.stepId()), attempt.attemptNo(),
                attempt.operationIdentity(), attempt.status().name(), attempt.leaseOwner(),
                date(attempt.leaseUntil()), date(attempt.claimedAt()), attempt.externalCallState().name(),
                attempt.externalRequestFingerprint(), attempt.providerRequestId(), attempt.provider(), attempt.model(),
                date(attempt.startedAt()), date(attempt.completedAt()), attempt.safeReasonCode(),
                attempt.outputResourceType(), id(attempt.outputResourceId()), attempt.versionNo())));
    }
    @Override public Optional<ContractReviewRun> findById(UUID runId) {
        return Optional.ofNullable(mapper.findRunById(id(runId))).map(this::run);
    }
    @Override public Optional<ContractReviewRun> findLatestByProjectIdAndContractIdAndRequestedBy(
            UUID projectId, UUID contractId, String requestedBy) {
        return Optional.ofNullable(mapper.findLatestByProjectIdAndContractIdAndRequestedBy(
                id(projectId), id(contractId), requestedBy)).map(this::run);
    }
    @Override public Optional<ContractReviewRun> findByIdForUpdate(UUID runId) {
        return Optional.ofNullable(mapper.findRunByIdForUpdate(id(runId))).map(this::run);
    }
    @Override public ContractReviewRun transition(ContractReviewRun expected, ContractReviewRun next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        int changed = mapper.updateRunCas(new ContractReviewRunPersistenceRow(
                id(next.runId()), id(next.projectId()), id(next.contractId()), next.contractVersion(),
                id(next.sourceCandidateId()), id(next.sourceConfirmationId()), next.runSchemaVersion(),
                next.contractSnapshotCanonicalJson(), next.contractSnapshotSha256(), next.reviewProfileVersion(),
                next.ruleVersion(), next.modelPolicyVersion(), next.inputSnapshotIdentity(), next.requestedBy(),
                next.status().name(), date(next.requestedAt()), date(next.startedAt()), date(next.completedAt()),
                date(next.failedAt()), date(next.unknownAt()), next.safeReasonCode(), next.versionNo()),
                expected.status().name(), expected.versionNo());
        requireChanged(changed);
        return next;
    }
    @Override public Optional<ContractReviewStep> findStepById(UUID stepId) {
        return Optional.ofNullable(mapper.findStepById(id(stepId))).map(this::step);
    }
    @Override public ContractReviewStep transition(ContractReviewStep expected, ContractReviewStep next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        int changed = mapper.updateStepCas(new ContractReviewStepPersistenceRow(
                id(next.stepId()), id(next.runId()), next.stepType().name(), next.required() ? 1 : 0,
                next.status().name(), next.stepPolicyVersion(), next.currentAttemptNo(),
                next.outputResourceType(), id(next.outputResourceId()), next.safeReasonCode(),
                date(next.startedAt()), date(next.completedAt()), next.versionNo()),
                expected.status().name(), expected.versionNo());
        requireChanged(changed);
        return next;
    }
    @Override public Optional<ContractReviewStepAttempt> findAttemptById(UUID attemptId) {
        return Optional.ofNullable(mapper.findAttemptById(id(attemptId))).map(this::attempt);
    }
    @Override public ContractReviewStepAttempt transition(ContractReviewStepAttempt expected,
            ContractReviewStepAttempt next) {
        ReviewStepTransitionGuard.requireValid(expected, next);
        int changed = mapper.updateAttemptCas(new ContractReviewStepAttemptPersistenceRow(
                id(next.attemptId()), id(next.runId()), id(next.stepId()), next.attemptNo(),
                next.operationIdentity(), next.status().name(), next.leaseOwner(),
                date(next.leaseUntil()), date(next.claimedAt()), next.externalCallState().name(),
                next.externalRequestFingerprint(), next.providerRequestId(), next.provider(), next.model(),
                date(next.startedAt()), date(next.completedAt()), next.safeReasonCode(),
                next.outputResourceType(), id(next.outputResourceId()), next.versionNo()),
                expected.status().name(), expected.versionNo());
        requireChanged(changed);
        return next;
    }
    @Override public List<ContractReviewRunMaterial> findMaterialsByRunId(UUID runId) {
        return mapper.findMaterialsByRunId(id(runId)).stream().map(this::material).toList();
    }
    @Override public List<ContractReviewStep> findStepsByRunId(UUID runId) {
        return mapper.findStepsByRunId(id(runId)).stream().map(this::step).toList();
    }
    @Override public List<ContractReviewStep> findByOutputResource(String resourceType, UUID resourceId) {
        return mapper.findStepsByOutputResource(resourceType, id(resourceId)).stream().map(this::step).toList();
    }
    @Override public List<ContractReviewStepAttempt> findAttemptsByRunId(UUID runId) {
        return mapper.findAttemptsByRunId(id(runId)).stream().map(this::attempt).toList();
    }

    private ContractReviewRun run(ContractReviewRunPersistenceRow row) {
        return new ContractReviewRun(uuid(row.runId()), uuid(row.projectId()), uuid(row.contractId()),
                row.contractVersion(), uuid(row.sourceCandidateId()), uuid(row.sourceConfirmationId()),
                row.runSchemaVersion(), row.contractSnapshotCanonicalJson(), row.contractSnapshotSha256(),
                row.reviewProfileVersion(), row.ruleVersion(), row.modelPolicyVersion(), row.inputSnapshotIdentity(),
                row.requestedBy(), ContractReviewRunStatus.valueOf(row.status()), instant(row.requestedAt()),
                instant(row.startedAt()), instant(row.completedAt()), instant(row.failedAt()),
                instant(row.unknownAt()), row.safeReasonCode(), row.versionNo());
    }
    private ContractReviewRunMaterial material(ContractReviewRunMaterialPersistenceRow row) {
        return new ContractReviewRunMaterial(uuid(row.runMaterialId()), uuid(row.runId()), row.ordinal(),
                uuid(row.projectId()), uuid(row.fileId()), row.fileSha256(), uuid(row.parseResultId()),
                row.parseVersion(), row.canonicalTextSha256(), DocumentRole.valueOf(row.effectiveRole()),
                uuid(row.roleDecisionId()), uuid(row.identityDecisionId()), uuid(row.sourceRoleDispositionId()),
                row.selectedBy(), instant(row.snapshottedAt()));
    }
    private ContractReviewStep step(ContractReviewStepPersistenceRow row) {
        return new ContractReviewStep(uuid(row.stepId()), uuid(row.runId()),
                ContractReviewStepType.valueOf(row.stepType()), row.requiredFlag() == 1,
                ContractReviewStepStatus.valueOf(row.status()), row.stepPolicyVersion(), row.currentAttemptNo(),
                row.outputResourceType(), uuid(row.outputResourceId()), row.safeReasonCode(),
                instant(row.startedAt()), instant(row.completedAt()), row.versionNo());
    }
    private ContractReviewStepAttempt attempt(ContractReviewStepAttemptPersistenceRow row) {
        return new ContractReviewStepAttempt(uuid(row.attemptId()), uuid(row.runId()), uuid(row.stepId()),
                row.attemptNo(), row.operationIdentity(), ContractReviewStepStatus.valueOf(row.status()),
                row.leaseOwner(), instant(row.leaseUntil()), instant(row.claimedAt()),
                ReviewStepExternalCallState.valueOf(row.externalCallState()), row.externalRequestFingerprint(),
                row.providerRequestId(), row.provider(), row.model(), instant(row.startedAt()),
                instant(row.completedAt()), row.safeReasonCode(), row.outputResourceType(),
                uuid(row.outputResourceId()), row.versionNo());
    }
    private static void requireOne(int updated) {
        if (updated != 1) throw new IllegalStateException("review run manifest write was not durable");
    }
    private static void requireChanged(int updated) {
        if (updated != 1) throw new BusinessException(BusinessErrorCode.CONFLICT,
                "review step transition conflict");
    }
    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static LocalDateTime date(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
