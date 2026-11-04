package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleEvidenceRepository;
import com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult;
import com.fuquan.audit.contract.domain.project.DocumentRoleCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RoleAssessmentTransactionCoordinator {
    public static final String SCOPE = "ROLE_ASSESSMENT";
    public static final String RESULT_RESOURCE = "PROJECT_FILE_ROLE_ASSESSMENT";
    private static final String RESOURCE = "PROJECT_FILE";

    private final ProjectFileRoleAssessmentRepository assessments;
    private final ProjectFileRoleEvidenceRepository evidence;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final RoleAssessmentRecoveryPolicy recoveryPolicy;
    private final FlowDebugLogger flow;

    public RoleAssessmentTransactionCoordinator(ProjectFileRoleAssessmentRepository assessments,
            ProjectFileRoleEvidenceRepository evidence, IdempotencyPort idempotency,
            OperationAuditPort audit, RoleAssessmentRecoveryPolicy recoveryPolicy, FlowDebugLogger flow) {
        this.assessments = assessments;
        this.evidence = evidence;
        this.idempotency = idempotency;
        this.audit = audit;
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy must not be null");
        this.flow = flow;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Start start(RoleAssessmentCommand command, CurrentUser actor, ProjectFileParseResult parse,
                       String fingerprint, UUID assessmentId, Instant occurredAt) {
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, command.idempotencyKey(), actor.userId(), RESOURCE, command.fileId().toString(),
                fingerprint, RESULT_RESOURCE, assessmentId.toString(), command.metadata().requestId(),
                command.metadata().traceId(), occurredAt));
        if (!claim.acquired()) {
            return replay(claim.record(), command, actor, parse, fingerprint, occurredAt);
        }
        ProjectFileRoleAssessment pending = ProjectFileRoleAssessment.pending(
                assessmentId, command.projectId(), command.fileId(), parse.fileSha256(),
                parse.parseResultId(), parse.parseVersion(), ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                RoleAssessmentService.ASSESSMENT_VERSION, RoleAssessmentService.ROLE_POLICY_VERSION,
                fingerprint, null, null, occurredAt);
        boolean inserted = false;
        try {
            ProjectFileRoleAssessment stored = assessments.stagePendingInsertIfAbsent(pending);
            if (!stored.equals(pending)) conflict("assessment identity collision");
            inserted = true;
            audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_STARTED", "roleAssessmentStart",
                    OperationAuditResultStatus.SUCCESS, "PENDING", actor, stored, command.metadata(), occurredAt,
                    "deterministic role assessment started"));
            assessments.commitStagedInsert(stored);
            flow.log("ROLE_ASSESSMENT_START_COMMIT", "CONTRACT", "APPLICATION", "roleAssessmentStart",
                    Map.of("assessmentId", assessmentId));
            return new Start(claim.record(), stored, null);
        } catch (RuntimeException failure) {
            if (inserted) compensateAssessmentInsert(pending, failure);
            compensateIdempotencyClaim(claim.record(), failure);
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Start startAi(RoleAssessmentCommand command, CurrentUser actor, ProjectFileParseResult parse,
            String fingerprint, UUID assessmentId, UUID precedingDeterministicAssessmentId,
            ProjectFileRoleModelProvenance provenance, Instant occurredAt) {
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, command.idempotencyKey(), actor.userId(), RESOURCE, command.fileId().toString(),
                fingerprint, RESULT_RESOURCE, assessmentId.toString(), command.metadata().requestId(),
                command.metadata().traceId(), occurredAt));
        if (!claim.acquired()) {
            return replay(claim.record(), command, actor, parse, fingerprint, occurredAt);
        }
        ProjectFileRoleAssessment pending = ProjectFileRoleAssessment.pending(
                assessmentId, command.projectId(), command.fileId(), parse.fileSha256(),
                parse.parseResultId(), parse.parseVersion(), ProjectFileRoleAssessmentSource.MACHINE_AI,
                AiRoleAssessmentService.ASSESSMENT_VERSION, RoleAssessmentService.ROLE_POLICY_VERSION,
                fingerprint, precedingDeterministicAssessmentId, provenance, occurredAt);
        boolean inserted = false;
        try {
            ProjectFileRoleAssessment stored = assessments.stagePendingInsertIfAbsent(pending);
            if (!stored.equals(pending)) conflict("assessment identity collision");
            inserted = true;
            audit.append(event("PROJECT_FILE_AI_ROLE_ASSESSMENT_STARTED", "aiRoleAssessmentStart",
                    OperationAuditResultStatus.SUCCESS, "PENDING", actor, stored, command.metadata(), occurredAt,
                    "AI role proposal started"));
            assessments.commitStagedInsert(stored);
            flow.log("AI_ROLE_ASSESSMENT_START_COMMIT", "CONTRACT", "APPLICATION", "aiRoleAssessmentStart",
                    Map.of("assessmentId", assessmentId));
            return new Start(claim.record(), stored, null);
        } catch (RuntimeException failure) {
            if (inserted) compensateAssessmentInsert(pending, failure);
            compensateIdempotencyClaim(claim.record(), failure);
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Start markRunning(Start start, Instant startedAt) {
        ProjectFileRoleAssessment running = start.assessment().start(startedAt);
        if (!assessments.compareAndSet(start.assessment(), running)) {
            conflict("assessment start concurrency conflict");
        }
        return new Start(start.idempotency(), running, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleAssessmentResult complete(Start start, DeterministicDocumentRoleResult result,
            CurrentUser actor, RoleAssessmentCommand command, Instant completedAt) {
        ProjectFileRoleAssessment running = start.assessment();
        List<ProjectFileRoleCandidateProjection> candidates = result.candidateRoles().stream()
                .map(RoleAssessmentTransactionCoordinator::candidate).toList();
        ProjectFileRoleAssessment completed = running.complete(result.proposedRole(), result.confidence(),
                result.conflict(), candidates, null, completedAt);
        List<ProjectFileRoleEvidence> proposedEvidence = result.evidence().stream()
                .map(signal -> evidence(UUID.randomUUID(), completed, signal, completed.createdAt())).toList();
        List<ProjectFileRoleEvidence> storedEvidence = evidence.insertBatchIfAbsent(proposedEvidence);
        for (ProjectFileRoleEvidence item : storedEvidence) requireEvidenceBinding(completed, item);
        if (!assessments.compareAndSet(running, completed)) {
            conflict("assessment completion concurrency conflict");
        }
        audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_COMPLETED", "roleAssessmentComplete",
                OperationAuditResultStatus.SUCCESS, result.proposedRole().name(), actor, completed,
                command.metadata(), completedAt, "deterministic role assessment completed"));
        idempotency.markSucceeded(start.idempotency(), RESULT_RESOURCE,
                completed.assessmentId().toString(), completedAt);
        flow.log("ROLE_ASSESSMENT_COMPLETE_COMMIT", "CONTRACT", "APPLICATION", "roleAssessmentComplete",
                Map.of("assessmentId", completed.assessmentId(), "evidenceCount", result.evidence().size()));
        return result(completed, storedEvidence, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleAssessmentResult completeAi(Start start, DeterministicDocumentRoleResult result,
            CurrentUser actor, RoleAssessmentCommand command, Instant completedAt) {
        ProjectFileRoleAssessment running = start.assessment();
        List<ProjectFileRoleCandidateProjection> candidates = result.candidateRoles().stream()
                .map(RoleAssessmentTransactionCoordinator::candidate).toList();
        ProjectFileRoleAssessment completed = running.complete(result.proposedRole(), result.confidence(),
                result.conflict(), candidates, null, completedAt);
        List<ProjectFileRoleEvidence> proposedEvidence = result.evidence().stream()
                .map(signal -> evidence(UUID.randomUUID(), completed, signal, completed.createdAt())).toList();
        List<ProjectFileRoleEvidence> storedEvidence = evidence.insertBatchIfAbsent(proposedEvidence);
        for (ProjectFileRoleEvidence item : storedEvidence) requireEvidenceBinding(completed, item);
        if (!assessments.compareAndSet(running, completed)) conflict("assessment completion concurrency conflict");
        audit.append(event("PROJECT_FILE_AI_ROLE_ASSESSMENT_COMPLETED", "aiRoleAssessmentComplete",
                OperationAuditResultStatus.SUCCESS, result.proposedRole().name(), actor, completed,
                command.metadata(), completedAt, "AI role proposal completed; human confirmation required"));
        idempotency.markSucceeded(start.idempotency(), RESULT_RESOURCE,
                completed.assessmentId().toString(), completedAt);
        flow.log("AI_ROLE_ASSESSMENT_COMPLETE_COMMIT", "CONTRACT", "APPLICATION", "aiRoleAssessmentComplete",
                Map.of("assessmentId", completed.assessmentId(), "evidenceCount", result.evidence().size()));
        return result(completed, storedEvidence, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Start start, CurrentUser actor, RoleAssessmentCommand command,
                     RuntimeException failure, Instant failedAt) {
        ProjectFileRoleAssessment running = start.assessment();
        ProjectFileRoleAssessment failed = running.finishWithoutProposal(RoleAssessmentExecutionStatus.FAILED,
                "ROLE_ASSESSMENT_EXECUTION_FAILED", failedAt);
        if (!assessments.compareAndSet(running, failed)) {
            conflict("assessment failure concurrency conflict");
        }
        audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_FAILED", "roleAssessmentFailure",
                OperationAuditResultStatus.FAILURE, "FAILED", actor, failed, command.metadata(), failedAt,
                failure.getClass().getSimpleName()));
        idempotency.markFailed(start.idempotency(), failedAt);
        flow.log("ROLE_ASSESSMENT_FAILURE_COMMIT", "CONTRACT", "APPLICATION", "roleAssessmentFailure",
                Map.of("assessmentId", failed.assessmentId(), "reasonCode", failed.safeReasonCode()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleAssessmentResult failAi(Start start, CurrentUser actor, RoleAssessmentCommand command,
            String safeReasonCode, RuntimeException failure, Instant failedAt) {
        ProjectFileRoleAssessment running = start.assessment();
        ProjectFileRoleAssessment failed = running.finishWithoutProposal(RoleAssessmentExecutionStatus.FAILED,
                safeReasonCode, failedAt);
        if (!assessments.compareAndSet(running, failed)) conflict("assessment failure concurrency conflict");
        audit.append(event("PROJECT_FILE_AI_ROLE_ASSESSMENT_FAILED", "aiRoleAssessmentFailure",
                OperationAuditResultStatus.FAILURE, "FAILED", actor, failed, command.metadata(), failedAt,
                safeReasonCode));
        idempotency.markFailed(start.idempotency(), failedAt);
        flow.log("AI_ROLE_ASSESSMENT_FAILURE_COMMIT", "CONTRACT", "APPLICATION", "aiRoleAssessmentFailure",
                Map.of("assessmentId", failed.assessmentId(), "reasonCode", safeReasonCode));
        return result(failed, List.of(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoleAssessmentResult reconcileCompleteFailure(Start start, CurrentUser actor,
            RoleAssessmentCommand command, RuntimeException original, Instant reconciledAt) {
        IdempotencyRecord durableIdempotency = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "role assessment completion outcome cannot be proven; assessmentId="
                                + start.assessment().assessmentId(), original));
        requireIdempotencyIdentity(durableIdempotency, command, actor,
                start.assessment().inputFingerprint(), start.assessment().assessmentId());
        ProjectFileRoleAssessment durable = assessments.findById(start.assessment().assessmentId())
                .orElseThrow(() -> new IllegalStateException(
                        "role assessment completion resource cannot be proven; assessmentId="
                                + start.assessment().assessmentId(), original));
        if (!durable.sameInputIdentity(start.assessment())) {
            throw new IllegalStateException("role assessment completion identity drift", original);
        }
        if (durable.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED) {
            if (durableIdempotency.status() == IdempotencyStatus.IN_PROGRESS) {
                audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_COMPLETION_RECONCILED",
                        "roleAssessmentCompletionReconcile", OperationAuditResultStatus.SUCCESS,
                        "COMPLETED", actor, durable, command.metadata(), reconciledAt,
                        "completion facts proven after uncertain response"));
                idempotency.markSucceeded(durableIdempotency, RESULT_RESOURCE,
                        durable.assessmentId().toString(), reconciledAt);
            } else if (durableIdempotency.status() != IdempotencyStatus.SUCCEEDED) {
                conflict("completed assessment has inconsistent idempotency status");
            }
            return reopenResult(durable, true);
        }
        if (durableIdempotency.status() != IdempotencyStatus.IN_PROGRESS) {
            conflict("incomplete assessment has terminal idempotency status");
        }
        if (durable.executionStatus() == RoleAssessmentExecutionStatus.UNKNOWN) {
            return reopenResult(durable, true);
        }
        if (!durable.equals(start.assessment())
                || durable.executionStatus() != RoleAssessmentExecutionStatus.RUNNING) {
            throw new IllegalStateException("role assessment completion state cannot be safely reconciled; assessmentId="
                    + durable.assessmentId(), original);
        }
        ProjectFileRoleAssessment unknown = durable.finishWithoutProposal(RoleAssessmentExecutionStatus.UNKNOWN,
                "ROLE_ASSESSMENT_COMPLETE_OUTCOME_UNKNOWN", reconciledAt);
        if (!assessments.compareAndSet(durable, unknown)) {
            throw new IllegalStateException("role assessment completion reconciliation lost CAS; assessmentId="
                    + durable.assessmentId(), original);
        }
        audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_UNKNOWN", "roleAssessmentReconcile",
                OperationAuditResultStatus.FAILURE, "UNKNOWN", actor, unknown, command.metadata(), reconciledAt,
                original.getClass().getSimpleName()));
        return result(unknown, List.of(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RoleAssessmentResult reopen(UUID projectId, UUID fileId, UUID assessmentId, boolean replayed) {
        ProjectFileRoleAssessment assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "role assessment not found"));
        if (!assessment.projectId().equals(projectId) || !assessment.fileId().equals(fileId)) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND, "role assessment not found");
        }
        return reopenResult(assessment, replayed);
    }

    private Start replay(IdempotencyRecord record, RoleAssessmentCommand command, CurrentUser actor,
                         ProjectFileParseResult parse, String fingerprint, Instant recoveredAt) {
        requireIdempotencyIdentity(record, command, actor, fingerprint, null);
        UUID assessmentId;
        try {
            assessmentId = UUID.fromString(record.resultResourceId());
        } catch (RuntimeException invalid) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency assessment identity is invalid");
        }
        ProjectFileRoleAssessment assessment = assessments.findById(assessmentId)
                .filter(value -> value.projectId().equals(command.projectId())
                        && value.fileId().equals(command.fileId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "role assessment not found"));
        RoleAssessmentResult result = reopenResult(assessment, true);
        if (!assessment.inputFingerprint().equals(fingerprint)
                || !assessment.fileSha256().equals(parse.fileSha256())
                || !assessment.parseResultId().equals(parse.parseResultId())
                || assessment.parseVersion() != parse.parseVersion()) {
            conflict("idempotent assessment durable state is inconsistent");
        }
        if (record.status() == IdempotencyStatus.FAILED) {
            if (assessment.executionStatus() != RoleAssessmentExecutionStatus.FAILED) {
                conflict("failed idempotency record has no failed assessment");
            }
            return new Start(record, assessment, result);
        }
        if (record.status() == IdempotencyStatus.SUCCEEDED) {
            if (assessment.executionStatus() != RoleAssessmentExecutionStatus.COMPLETED) {
                conflict("completed idempotency record has no completed assessment");
            }
            return new Start(record, assessment, result);
        }
        if (assessment.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED) {
            audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_COMPLETION_RECONCILED",
                    "roleAssessmentCompletionReconcile", OperationAuditResultStatus.SUCCESS,
                    "COMPLETED", actor, assessment, command.metadata(), recoveredAt,
                    "completion facts proven during same-key recovery"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, assessment.assessmentId().toString(), recoveredAt);
            return new Start(record, assessment, result);
        }
        if (assessment.executionStatus() == RoleAssessmentExecutionStatus.PENDING
                || assessment.executionStatus() == RoleAssessmentExecutionStatus.UNKNOWN) {
            return new Start(record, assessment, null);
        }
        if (assessment.executionStatus() == RoleAssessmentExecutionStatus.RUNNING) {
            if (!recoveryPolicy.isExpired(assessment.startedAt(), recoveredAt)) {
                return new Start(record, assessment, result);
            }
            ProjectFileRoleAssessment unknown = assessment.finishWithoutProposal(
                    RoleAssessmentExecutionStatus.UNKNOWN, "ROLE_ASSESSMENT_RECOVERY_TAKEOVER", recoveredAt);
            if (!assessments.compareAndSet(assessment, unknown)) {
                conflict("role assessment recovery concurrency conflict");
            }
            audit.append(event("PROJECT_FILE_ROLE_ASSESSMENT_UNKNOWN", "roleAssessmentRecoveryTakeover",
                    OperationAuditResultStatus.FAILURE, "UNKNOWN", actor, unknown, command.metadata(), recoveredAt,
                    "same-key deterministic recovery fenced previous attempt"));
            return new Start(record, unknown, null);
        }
        if (assessment.executionStatus() != RoleAssessmentExecutionStatus.PENDING
                && assessment.executionStatus() != RoleAssessmentExecutionStatus.UNKNOWN) {
            conflict("in-progress idempotency record has inconsistent assessment state");
        }
        return new Start(record, assessment, null);
    }

    private RoleAssessmentResult reopenResult(ProjectFileRoleAssessment assessment, boolean replayed) {
        List<ProjectFileRoleEvidence> storedEvidence = evidence.findByAssessmentId(assessment.assessmentId());
        for (ProjectFileRoleEvidence item : storedEvidence) requireEvidenceBinding(assessment, item);
        List<ProjectFileRoleEvidence> visibleEvidence =
                assessment.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED
                        ? storedEvidence : List.of();
        return result(assessment, visibleEvidence, replayed);
    }

    private static RoleAssessmentResult result(ProjectFileRoleAssessment assessment,
                                               List<ProjectFileRoleEvidence> storedEvidence,
                                               boolean replayed) {
        RoleDecisionStatus decision = assessment.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED
                ? RoleDecisionStatus.NEEDS_CONFIRMATION : null;
        List<ProjectFileRoleEvidence> ordered = storedEvidence.stream()
                .sorted(java.util.Comparator.comparing(ProjectFileRoleEvidence::sourceLocator)
                        .thenComparing(value -> value.evidenceId().toString()))
                .toList();
        return new RoleAssessmentResult(assessment, decision, ordered, replayed);
    }

    private static void requireIdempotencyIdentity(IdempotencyRecord record, RoleAssessmentCommand command,
            CurrentUser actor, String fingerprint, UUID expectedAssessmentId) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType()) || !command.fileId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())
                || !RESULT_RESOURCE.equals(record.resultResourceType())
                || (expectedAssessmentId != null
                && !expectedAssessmentId.toString().equals(record.resultResourceId()))) {
            conflict("idempotency key fingerprint conflict");
        }
    }

    private void compensateAssessmentInsert(ProjectFileRoleAssessment pending, RuntimeException original) {
        try {
            assessments.rollbackUncommittedInsert(pending);
        } catch (RuntimeException compensation) {
            original.addSuppressed(compensation);
        }
    }

    private void compensateIdempotencyClaim(IdempotencyRecord record, RuntimeException original) {
        try {
            idempotency.rollbackUncommittedClaim(record);
        } catch (RuntimeException compensation) {
            original.addSuppressed(compensation);
        }
    }

    private static ProjectFileRoleCandidateProjection candidate(DocumentRoleCandidate value) {
        String reasonCode = value.evidence().get(0).ruleCode();
        return new ProjectFileRoleCandidateProjection(value.role(), value.score(), value.confidence(), reasonCode);
    }

    private static ProjectFileRoleEvidence evidence(UUID id, ProjectFileRoleAssessment assessment,
                                                     DocumentRoleSignal signal, Instant createdAt) {
        if (!assessment.parseResultId().equals(signal.parseResultId())) {
            throw new IllegalArgumentException("role signal parse identity mismatch");
        }
        return new ProjectFileRoleEvidence(id, assessment.assessmentId(), assessment.projectId(), assessment.fileId(),
                assessment.fileSha256(), assessment.parseResultId(), assessment.parseVersion(), signal.role(),
                signal.signalType(), signal.normalizedValue(), signal.ruleCode(), signal.weight(),
                signal.sourceLocator(), signal.charStart(), signal.charEnd(), signal.textHash(), signal.excerpt(),
                signal.extractorVersion(), createdAt);
    }

    private static void requireEvidenceBinding(ProjectFileRoleAssessment assessment, ProjectFileRoleEvidence item) {
        if (!item.assessmentId().equals(assessment.assessmentId())
                || !item.projectId().equals(assessment.projectId()) || !item.fileId().equals(assessment.fileId())
                || !item.fileSha256().equals(assessment.fileSha256())
                || !item.parseResultId().equals(assessment.parseResultId())
                || item.parseVersion() != assessment.parseVersion()) {
            throw new IllegalStateException("role evidence binding is inconsistent");
        }
    }

    private static OperationAuditEvent event(String type, String action, OperationAuditResultStatus status,
            String businessResult, CurrentUser actor, ProjectFileRoleAssessment assessment,
            ProjectRequestMetadata metadata, Instant occurredAt, String detail) {
        return new OperationAuditEvent(type, "CONTRACT", action, actor.userId(), actor.username(),
                RESULT_RESOURCE, assessment.assessmentId().toString(), null, status, businessResult,
                action, detail, metadata.requestId(), metadata.traceId(), metadata.sourceIp(), occurredAt);
    }

    private static void conflict(String message) {
        throw new BusinessException(BusinessErrorCode.CONFLICT, message);
    }

    public record Start(IdempotencyRecord idempotency, ProjectFileRoleAssessment assessment,
                        RoleAssessmentResult replay) {
        public Start {
            Objects.requireNonNull(idempotency, "idempotency must not be null");
            Objects.requireNonNull(assessment, "assessment must not be null");
        }
        public boolean isReplay() { return replay != null; }
    }
}
