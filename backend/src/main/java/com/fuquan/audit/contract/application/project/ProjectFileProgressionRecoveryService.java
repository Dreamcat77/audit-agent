package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectFileProgressionRecoveryTransactionCoordinator.StageResult;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionService;
import com.fuquan.audit.contract.application.project.factai.ContractFactSegmentationException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidateException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentService;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjectionService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentTransactionCoordinator;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.fuquan.audit.common.web.RequestIdFilter;

@Service
public class ProjectFileProgressionRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectFileProgressionRecoveryService.class);
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final ProjectFileRepository files;
    private final ProjectRepository projects;
    private final ProjectFileRoleProjectionService roleProjections;
    private final ProjectFileIdentityProjectionService identityProjections;
    private final ProjectFileProgressionProjectionService progressionProjections;
    private final ProjectIntakeProgressionService intake;
    private final RoleAssessmentService roleAssessments;
    private final IdentityAssessmentService identityAssessments;
    private final ProjectFileRoleDecisionRepository roleDecisions;
    private final ProjectFileIdentityDecisionRepository identityDecisions;
    private final ContractCandidateRepository candidates;
    private final ContractCandidateMaterializationService materialization;
    private final ContractFactExtractionService factExtraction;
    private final ContractFactExtractionRepository factExtractionRepository;
    private final ContractFactCandidatePort factCandidates;
    private final ProjectFileProgressionRecoveryTransactionCoordinator transactions;
    private final AuthContext auth;
    private final Clock clock;

    @Autowired
    public ProjectFileProgressionRecoveryService(ProjectFileRepository files, ProjectRepository projects,
            ProjectFileRoleProjectionService roleProjections,
            ProjectFileIdentityProjectionService identityProjections,
            ProjectFileProgressionProjectionService progressionProjections,
            ProjectIntakeProgressionService intake, RoleAssessmentService roleAssessments,
            IdentityAssessmentService identityAssessments,
            ProjectFileRoleDecisionRepository roleDecisions,
            ProjectFileIdentityDecisionRepository identityDecisions,
            ContractCandidateRepository candidates,
            ContractCandidateMaterializationService materialization,
            ContractFactExtractionService factExtraction,
            ContractFactExtractionRepository factExtractionRepository,
            ContractFactCandidatePort factCandidates,
            ProjectFileProgressionRecoveryTransactionCoordinator transactions,
            AuthContext auth, Clock clock) {
        this.files = files;
        this.projects = projects;
        this.roleProjections = roleProjections;
        this.identityProjections = identityProjections;
        this.progressionProjections = progressionProjections;
        this.intake = intake;
        this.roleAssessments = roleAssessments;
        this.identityAssessments = identityAssessments;
        this.roleDecisions = roleDecisions;
        this.identityDecisions = identityDecisions;
        this.candidates = candidates;
        this.materialization = materialization;
        this.factExtraction = factExtraction;
        this.factExtractionRepository = factExtractionRepository;
        this.factCandidates = factCandidates;
        this.transactions = transactions;
        this.auth = auth;
        this.clock = clock;
    }

    ProjectFileProgressionRecoveryService(ProjectFileRepository files, ProjectRepository projects,
            ProjectFileRoleProjectionService roleProjections,
            ProjectFileIdentityProjectionService identityProjections,
            ProjectFileProgressionProjectionService progressionProjections,
            ProjectIntakeProgressionService intake, RoleAssessmentService roleAssessments,
            IdentityAssessmentService identityAssessments,
            ProjectFileRoleDecisionRepository roleDecisions,
            ProjectFileIdentityDecisionRepository identityDecisions,
            ContractCandidateRepository candidates,
            ContractCandidateMaterializationService materialization,
            ProjectFileProgressionRecoveryTransactionCoordinator transactions,
            AuthContext auth, Clock clock) {
        this(files, projects, roleProjections, identityProjections, progressionProjections, intake,
                roleAssessments, identityAssessments, roleDecisions, identityDecisions, candidates,
                materialization, null, null, null, transactions, auth, clock);
    }

    public ProjectFileProgressionRecoveryResult resume(ProjectFileProgressionRecoveryCommand command) {
        validateKey(command.idempotencyKey());
        CurrentUser actor = actor();
        log(command, actor, "PROGRESSION_PRECHECK", "BEFORE_OUTER_CLAIM", "NONE",
                ProjectFileProgressionRecoveryTransactionCoordinator.SCOPE);

        Optional<IdempotencyRecord> original = transactions.find(command, actor);
        if (original.isPresent()) {
            log(command, actor, "PROGRESSION_PRECHECK", "EXISTING_" + original.orElseThrow().status(),
                    "DURABLE_RECORD_FOUND", ProjectFileProgressionRecoveryTransactionCoordinator.SCOPE);
            return resumeOriginal(command, actor, original.orElseThrow());
        }

        Snapshot snapshot = current(command.projectId(), command.fileId());
        try {
            requireExpected(snapshot.progression(), command, false);
        } catch (ProjectFileProgressionRecoveryException rejected) {
            log(command, actor, "PROGRESSION_PRECHECK", "REJECTED_BEFORE_OUTER_CLAIM",
                    rejected.classification().name(),
                    ProjectFileProgressionRecoveryTransactionCoordinator.SCOPE);
            throw rejected;
        }
        var start = transactions.claim(command, actor);
        log(command, actor, "PROGRESSION_PRECHECK",
                start.acquired() ? "OUTER_CLAIM_ACQUIRED" : "OUTER_CLAIM_REPLAYED",
                "NONE", ProjectFileProgressionRecoveryTransactionCoordinator.SCOPE);
        if (!start.acquired()) {
            return resumeOriginal(command, actor, start.record());
        }
        return executeAndComplete(command, actor, start.record(), snapshot, false);
    }

    private ProjectFileProgressionRecoveryResult resumeOriginal(
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor,
            IdempotencyRecord original) {
        if (original.status() == IdempotencyStatus.SUCCEEDED) {
            verifyResult(original, command, actor);
            return new ProjectFileProgressionRecoveryResult(requireFile(command), true);
        }
        if (original.status() == IdempotencyStatus.FAILED) {
            throw error("progression recovery was deterministically rejected",
                    ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        }

        Optional<StageResult> recovered = recoverInnerResult(command, actor, original);
        if (recovered.isPresent()) {
            completeWithReconciliation(original, command, actor, recovered.orElseThrow());
            return new ProjectFileProgressionRecoveryResult(requireFile(command), true);
        }

        throw error("progression recovery is already in progress or its outcome is uncertain",
                ProjectFileProgressionRecoveryException.Classification.RECOVERY_IN_PROGRESS);
    }

    private ProjectFileProgressionRecoveryResult executeAndComplete(
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor,
            IdempotencyRecord outer, Snapshot snapshot, boolean replayed) {
        StageResult result;
        var stage = transactions.claimStage(command, actor);
        if (!stage.acquired()) {
            if (stage.record().status() == IdempotencyStatus.SUCCEEDED) {
                result = stageResult(stage.record());
                verifyStageResult(result, command, actor, snapshot);
                completeWithReconciliation(outer, command, actor, result);
                return new ProjectFileProgressionRecoveryResult(requireFile(command), true);
            }
            throw error("progression recovery stage is already in progress",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IN_PROGRESS);
        }
        try {
            result = execute(command, actor, snapshot);
            verifyStageResult(result, command, actor, snapshot);
            transactions.completeStage(stage.record(), command, actor, result);
        } catch (ProjectFileProgressionRecoveryException known) {
            if (known.classification() == ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN
                    || known.classification() == ProjectFileProgressionRecoveryException.Classification.RECOVERY_IN_PROGRESS) {
                log(command, actor, "RECOVERY_TERMINAL", "OUTCOME_UNCERTAIN", known.classification().name(),
                        ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
                throw known;
            }
            transactions.fail(stage.record(), command, actor);
            transactions.fail(outer, command, actor);
            log(command, actor, "RECOVERY_TERMINAL", "FAILED", known.classification().name(),
                    ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
            throw known;
        } catch (ContractFactSegmentationException rejected) {
            try {
                transactions.fail(stage.record(), command, actor);
                transactions.fail(outer, command, actor);
            } catch (RuntimeException closureUncertain) {
                log(command, actor, "RECOVERY_TERMINAL", "OUTCOME_UNCERTAIN",
                        "RECOVERY_OUTCOME_UNCERTAIN",
                        ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
                throw new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                        "progression recovery failure closure is uncertain",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN,
                        closureUncertain);
            }
            log(command, actor, "RECOVERY_TERMINAL", "FAILED", rejected.reasonCode(),
                    ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
            throw error("contract fact material exceeds the supported extraction range",
                    ProjectFileProgressionRecoveryException.Classification.FACT_SEGMENT_BUDGET_EXCEEDED);
        } catch (ContractFactCandidateException rejected) {
            if (ContractFactExtractionAttempt.failureStatus(rejected.getReason().name())
                    == ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN) {
                log(command, actor, "RECOVERY_TERMINAL", "OUTCOME_UNCERTAIN", rejected.getReason().name(),
                        ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
                throw new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                        "contract fact extraction outcome is uncertain",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN,
                        rejected);
            }
            transactions.fail(stage.record(), command, actor);
            transactions.fail(outer, command, actor);
            log(command, actor, "RECOVERY_TERMINAL", "FAILED", rejected.getReason().name(),
                    ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
            throw error("contract fact extraction was deterministically rejected",
                    ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        } catch (RuntimeException uncertain) {
            log(command, actor, "RECOVERY_TERMINAL", "OUTCOME_UNCERTAIN",
                    "RECOVERY_OUTCOME_UNCERTAIN",
                    ProjectFileProgressionRecoveryTransactionCoordinator.STAGE_SCOPE);
            throw new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                    "progression recovery outcome is uncertain",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN,
                    uncertain);
        }
        completeWithReconciliation(outer, command, actor, result);
        log(command, actor, "RECOVERY_TERMINAL", "SUCCEEDED", "NONE",
                ProjectFileProgressionRecoveryTransactionCoordinator.SCOPE);
        return new ProjectFileProgressionRecoveryResult(requireFile(command), replayed);
    }

    private void completeWithReconciliation(IdempotencyRecord outer,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor, StageResult result) {
        try {
            transactions.complete(outer, command, actor, result);
        } catch (RuntimeException uncertain) {
            Optional<IdempotencyRecord> durable = transactions.find(command, actor);
            if (durable.isPresent() && durable.orElseThrow().status() == IdempotencyStatus.SUCCEEDED) {
                verifyResult(durable.orElseThrow(), command, actor);
                return;
            }
            throw new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                    "progression recovery completion is uncertain",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN,
                    uncertain);
        }
    }

    private StageResult execute(ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor, Snapshot snapshot) {
        return switch (command.expectedBlocker()) {
            case "ROLE_ASSESSMENT_PENDING" -> {
                String key = ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "role", command, actor);
                var result = intake.ensureRoleAssessment(command.projectId(), command.fileId(),
                        key, command.metadata());
                yield new StageResult(RoleAssessmentTransactionCoordinator.RESULT_RESOURCE,
                        result.assessment().assessmentId());
            }
            case "IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE" -> {
                if (snapshot.role().roleDecisionId() == null) {
                    throw error("current role decision identity is unavailable",
                            ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
                }
                String key = ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "identity", command, actor);
                var result = intake.ensureIdentityAssessment(command.projectId(), command.fileId(),
                        key, command.metadata());
                yield new StageResult(
                        com.fuquan.audit.contract.application.project.identity
                                .IdentityAssessmentTransactionCoordinator.RESULT_RESOURCE,
                        result.assessment().assessmentId());
            }
            case "CANDIDATE_MATERIALIZATION_PENDING" -> {
                UUID roleDecisionId = snapshot.role().roleDecisionId();
                var identityDecision = snapshot.identity().currentHumanSameDecision();
                if (roleDecisionId == null || identityDecision == null) {
                    throw error("current human authority tuple is unavailable",
                            ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
                }
                var roleDecision = roleDecisions.findById(roleDecisionId)
                        .orElseThrow(() -> error("current role decision is unavailable",
                                ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
                var storedIdentity = identityDecisions.findById(identityDecision.decisionId())
                        .orElseThrow(() -> error("current identity decision is unavailable",
                                ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
                var result = materialization.materializeFromDecision(roleDecision, storedIdentity,
                        actor, command.metadata());
                yield new StageResult(ContractCandidateMaterializationTransactionCoordinator.RESULT_RESOURCE,
                        result.candidate().id());
            }
            case "FACT_EXTRACTION_PENDING" -> {
                if (factExtraction == null) {
                    throw error("contract fact extraction is unavailable",
                            ProjectFileProgressionRecoveryException.Classification.RECOVERY_ACTION_NOT_ALLOWED);
                }
                String key = ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, actor);
                var result = factExtraction.extract(command.projectId(), command.fileId(), key,
                        actor, command.metadata());
                yield new StageResult(ContractFactExtractionService.RESULT_RESOURCE,
                        result.attemptId());
            }
            default -> throw error("blocker is not machine recoverable",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_ACTION_NOT_ALLOWED);
        };
    }

    private Optional<StageResult> recoverInnerResult(ProjectFileProgressionRecoveryCommand command,
            CurrentUser actor, IdempotencyRecord outer) {
        Optional<IdempotencyRecord> stage = transactions.findStage(command, actor);
        if (stage.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord durableStage = stage.orElseThrow();
        if (durableStage.status() == IdempotencyStatus.SUCCEEDED) {
            StageResult result = stageResult(durableStage);
            verifyStageResult(result, command, actor, null);
            return Optional.of(result);
        }
        if ((durableStage.status() != IdempotencyStatus.IN_PROGRESS
                && durableStage.status() != IdempotencyStatus.FAILED)
                || !"FACT_EXTRACTION_PENDING".equals(command.expectedBlocker())
                || factExtractionRepository == null) {
            return Optional.empty();
        }
        String operationKey = ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                "contract-fact", command, actor);
        var found = factExtractionRepository.findByOperation(actor.userId(), operationKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ContractFactExtractionAttempt attempt = found.orElseThrow();
        verifyFactAttemptIdentity(attempt, command, actor, null);
        if (attempt.status() == ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN) {
            throw error("contract fact extraction outcome is uncertain",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
        }
        if (attempt.status() == ContractFactExtractionAttempt.Status.FAILED) {
            if (attempt.failureReason() == null
                    || !attempt.failureReason().matches("[A-Z0-9_]{1,200}")
                    || attempt.acceptedCount() != 0
                    || !factExtractionRepository.findCandidates(attempt.attemptId()).isEmpty()) {
                return Optional.empty();
            }
            transactions.fail(durableStage, command, actor);
            transactions.fail(outer, command, actor);
            throw error("contract fact extraction was deterministically rejected",
                    ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        }
        if (attempt.status() != ContractFactExtractionAttempt.Status.SUCCEEDED) {
            return Optional.empty();
        }
        StageResult recovered = new StageResult(ContractFactExtractionService.RESULT_RESOURCE,
                attempt.attemptId());
        verifyStageResult(recovered, command, actor, null);
        completeRecoveredStage(durableStage, command, actor, recovered);
        return Optional.of(recovered);
    }

    private void completeRecoveredStage(IdempotencyRecord stage,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor, StageResult result) {
        try {
            transactions.completeStage(stage, command, actor, result);
        } catch (RuntimeException uncertain) {
            Optional<IdempotencyRecord> durable = transactions.findStage(command, actor);
            if (durable.isPresent() && durable.orElseThrow().status() == IdempotencyStatus.SUCCEEDED) {
                StageResult persisted = stageResult(durable.orElseThrow());
                verifyStageResult(persisted, command, actor, null);
                if (persisted.equals(result)) return;
            }
            throw new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                    "progression recovery stage completion is uncertain",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN,
                    uncertain);
        }
    }

    private void verifyResult(IdempotencyRecord record,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor) {
        UUID resultId;
        try {
            resultId = UUID.fromString(record.resultResourceId());
        } catch (RuntimeException invalid) {
            throw error("completed recovery result identity is invalid",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
        }
        verifyStageResult(new StageResult(record.resultResourceType(), resultId), command, actor, null);
    }

    private void verifyStageResult(StageResult result,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor, Snapshot authorized) {
        if (RoleAssessmentTransactionCoordinator.RESULT_RESOURCE.equals(result.resultResourceType())) {
            var assessment = roleAssessments.get(command.projectId(), command.fileId(),
                    result.resultResourceId()).assessment();
            requireTuple(assessment.projectId(), assessment.fileId(), command);
            if (authorized != null && (!assessment.fileSha256().equals(authorized.file().sha256())
                    || !assessment.parseResultId().equals(authorized.role().parseResultId())
                    || assessment.parseVersion() != authorized.role().parseVersion())) {
                throw error("role recovery result does not match the authorized input",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
            }
            return;
        }
        if (com.fuquan.audit.contract.application.project.identity
                .IdentityAssessmentTransactionCoordinator.RESULT_RESOURCE.equals(
                        result.resultResourceType())) {
            var assessment = identityAssessments.get(command.projectId(), command.fileId(),
                    result.resultResourceId()).assessment();
            requireTuple(assessment.projectId(), assessment.fileId(), command);
            if (authorized != null && (assessment.projectVersionNo() != authorized.project().versionNo()
                    || !assessment.fileSha256().equals(authorized.file().sha256())
                    || !assessment.parseResultId().equals(authorized.role().parseResultId())
                    || assessment.parseVersion() != authorized.role().parseVersion())) {
                throw error("identity recovery result does not match the authorized input",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
            }
            return;
        }
        if (ContractCandidateMaterializationTransactionCoordinator.RESULT_RESOURCE.equals(
                result.resultResourceType())) {
            ContractCandidate candidate = candidates.findByProjectIdAndId(
                    command.projectId(), result.resultResourceId())
                    .orElseThrow(() -> error("materialized candidate result is unavailable",
                            ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
            requireTuple(candidate.projectId(), candidate.sourceContractFileId(), command);
            if (authorized != null && (!java.util.Objects.equals(candidate.sourceRoleDecisionId(),
                        authorized.role().roleDecisionId())
                    || authorized.identity().currentHumanSameDecision() == null
                    || !java.util.Objects.equals(candidate.sourceIdentityDecisionId(),
                        authorized.identity().currentHumanSameDecision().decisionId()))) {
                throw error("candidate recovery result does not match the authorized authority tuple",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
            }
            return;
        }
        if (ContractFactExtractionService.RESULT_RESOURCE.equals(result.resultResourceType())) {
            if (factExtractionRepository == null) {
                throw error("contract fact extraction result repository is unavailable",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
            }
            var attempt = factExtractionRepository.findById(result.resultResourceId())
                    .orElseThrow(() -> error("contract fact extraction result is unavailable",
                            ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
            requireTuple(attempt.projectId(), attempt.fileId(), command);
            verifyFactAttemptIdentity(attempt, command, actor, authorized);
            if (attempt.status() == ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN) {
                throw error("contract fact extraction outcome is uncertain",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
            }
            if (attempt.status() == ContractFactExtractionAttempt.Status.FAILED) {
                throw error("contract fact extraction was deterministically rejected",
                        ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
            }
            if (attempt.status() != ContractFactExtractionAttempt.Status.SUCCEEDED) {
                throw error("contract fact extraction is still in progress",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IN_PROGRESS);
            }
            return;
        }
        throw error("completed recovery result type is unsupported",
                ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
    }

    private void verifyFactAttemptIdentity(ContractFactExtractionAttempt attempt,
            ProjectFileProgressionRecoveryCommand command, CurrentUser actor, Snapshot authorized) {
        requireTuple(attempt.projectId(), attempt.fileId(), command);
        String expectedOperationKey = ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                "contract-fact", command, actor);
        if (factCandidates == null
                || !java.util.Objects.equals(attempt.actorId(), actor.userId())
                || !java.util.Objects.equals(attempt.operationKey(), expectedOperationKey)
                || !java.util.Objects.equals(attempt.provenance(), factCandidates.configuredProvenance())) {
            throw error("contract fact extraction result does not match the original operation identity",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
        }
        var roleDecision = roleDecisions.findById(attempt.roleDecisionId())
                .orElseThrow(() -> error("contract fact extraction role authority is unavailable",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
        var identityDecision = identityDecisions.findById(attempt.identityDecisionId())
                .orElseThrow(() -> error("contract fact extraction identity authority is unavailable",
                        ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN));
        if (!roleDecision.projectId().equals(attempt.projectId())
                || !roleDecision.fileId().equals(attempt.fileId())
                || !roleDecision.fileSha256().equals(attempt.fileSha256())
                || !roleDecision.parseResultId().equals(attempt.parseResultId())
                || roleDecision.parseVersion() != attempt.parseVersion()
                || roleDecision.selectedRole() != attempt.effectiveRole()
                || !identityDecision.projectId().equals(attempt.projectId())
                || !identityDecision.fileId().equals(attempt.fileId())
                || !identityDecision.fileSha256().equals(attempt.fileSha256())
                || !identityDecision.parseResultId().equals(attempt.parseResultId())
                || identityDecision.parseVersion() != attempt.parseVersion()
                || identityDecision.resolution()
                        != com.fuquan.audit.contract.domain.project.ProjectIdentityResolution.CONFIRM_SAME_PROJECT) {
            throw error("contract fact extraction result authority tuple is inconsistent",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
        }
        if (authorized != null && (!attempt.fileSha256().equals(authorized.file().sha256())
                || !attempt.parseResultId().equals(authorized.role().parseResultId())
                || attempt.parseVersion() != authorized.role().parseVersion()
                || attempt.effectiveRole() != authorized.role().effectiveRole()
                || !java.util.Objects.equals(attempt.roleDecisionId(), authorized.role().roleDecisionId())
                || authorized.identity().currentHumanSameDecision() == null
                || !java.util.Objects.equals(attempt.identityDecisionId(),
                        authorized.identity().currentHumanSameDecision().decisionId()))) {
            throw error("contract fact extraction result does not match the authorized input",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
        }
    }

    private static void requireTuple(UUID projectId, UUID fileId,
            ProjectFileProgressionRecoveryCommand command) {
        if (!command.projectId().equals(projectId) || !command.fileId().equals(fileId)) {
            throw error("recovery result does not belong to the requested project file",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
        }
    }

    private Snapshot current(UUID projectId, UUID fileId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ProjectFile file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "project file not found"));
        ProjectFileRoleProjection role = roleProjections.project(file);
        EffectiveProjectIdentity identity = identityProjections.project(file);
        return new Snapshot(project, file, role, identity,
                progressionProjections.project(file, role, identity));
    }

    private static StageResult stageResult(IdempotencyRecord record) {
        try {
            return new StageResult(record.resultResourceType(), UUID.fromString(record.resultResourceId()));
        } catch (RuntimeException invalid) {
            throw error("durable recovery stage result identity is invalid",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN);
        }
    }

    private ProjectFile requireFile(ProjectFileProgressionRecoveryCommand command) {
        return files.findByProjectIdAndId(command.projectId(), command.fileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "project file not found"));
    }

    private static void requireExpected(ProjectFileProgressionProjection current,
            ProjectFileProgressionRecoveryCommand command, boolean originalInProgress) {
        String expectedAction = ProjectFileProgressionRecoveryTransactionCoordinator.actionFor(
                command.expectedBlocker());
        if (!command.expectedBlocker().equals(current.blocker())
                || !current.availableActions().contains(expectedAction)) {
            throw error(originalInProgress
                            ? "original progression recovery result cannot be proven"
                            : "requested progression action is no longer authorized",
                    originalInProgress
                            ? ProjectFileProgressionRecoveryException.Classification.RECOVERY_IDENTITY_UNPROVEN
                            : ProjectFileProgressionRecoveryException.Classification.RECOVERY_ACTION_NOT_ALLOWED);
        }
        if (!command.expectedRecoveryInputFingerprint().equals(
                current.recoveryInputFingerprint())) {
            throw error("progression recovery input changed",
                    ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
        }
    }

    private CurrentUser actor() {
        return auth.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static void validateKey(String key) {
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        }
    }

    private static ProjectFileProgressionRecoveryException error(String message,
            ProjectFileProgressionRecoveryException.Classification classification) {
        return new ProjectFileProgressionRecoveryException(BusinessErrorCode.CONFLICT,
                message, classification);
    }

    private static void log(ProjectFileProgressionRecoveryCommand command, CurrentUser actor,
            String stage, String outcome, String reasonCode, String scope) {
        String blocker = switch (command.expectedBlocker()) {
            case "ROLE_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE",
                    "CANDIDATE_MATERIALIZATION_PENDING", "FACT_EXTRACTION_PENDING" ->
                    command.expectedBlocker();
            default -> "UNSUPPORTED_BLOCKER";
        };
        String action = switch (blocker) {
            case "ROLE_ASSESSMENT_PENDING" -> "RESUME_ROLE_ASSESSMENT";
            case "IDENTITY_ASSESSMENT_PENDING", "IDENTITY_ASSESSMENT_STALE" ->
                    "RESUME_IDENTITY_ASSESSMENT";
            case "CANDIDATE_MATERIALIZATION_PENDING" -> "RESUME_CANDIDATE_MATERIALIZATION";
            case "FACT_EXTRACTION_PENDING" -> "RESUME_FACT_EXTRACTION";
            default -> "UNSUPPORTED_ACTION";
        };
        LOGGER.info("event={} diagnosticSchemaVersion=1 module=CONTRACT layer=APPLICATION "
                        + "operation=resumeProjectFileProgression stage={} outcome={} reasonCode={} "
                        + "requestId={} traceId={} projectId={} fileId={} attemptId=null "
                        + "idempotencyScope={} operationKeyHash={} durationMs=0 blocker={} action={}",
                stage, stage, outcome, reasonCode, MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"),
                command.projectId(), command.fileId(), scope,
                ProjectFileProgressionRecoveryTransactionCoordinator.durableKey(
                        actor.userId(), command.idempotencyKey()),
                blocker, action);
    }

    private record Snapshot(Project project, ProjectFile file, ProjectFileRoleProjection role,
                            EffectiveProjectIdentity identity,
                            ProjectFileProgressionProjection progression) { }
}
