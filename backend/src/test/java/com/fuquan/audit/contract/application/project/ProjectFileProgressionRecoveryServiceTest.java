package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidateException;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionResult;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionService;
import com.fuquan.audit.contract.application.project.factai.ContractFactSegmentationException;
import com.fuquan.audit.contract.application.project.identity.ProjectFileIdentityProjectionService;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjectionService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentResult;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentService;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentService;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ProjectFileProgressionRecoveryServiceTest {
    private static final UUID PROJECT = UUID.fromString("33000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("33000000-0000-0000-0000-000000000002");
    private static final UUID ASSESSMENT = UUID.fromString("33000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-23T01:00:00Z");
    private static final UUID ROLE_DECISION = UUID.fromString("33000000-0000-0000-0000-000000000005");
    private static final UUID IDENTITY_DECISION = UUID.fromString("33000000-0000-0000-0000-000000000006");
    private static final ContractFactCandidatePort.Provenance FACT_PROVENANCE =
            new ContractFactCandidatePort.Provenance("provider", "model", "revision", "prompt", "schema");
    private static final CurrentUser ACTOR = new CurrentUser("actor-1", "actor", "Actor", Set.of("AUDITOR"));

    private final ProjectFileRepository files = mock(ProjectFileRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectFileRoleProjectionService roleProjections = mock(ProjectFileRoleProjectionService.class);
    private final ProjectFileIdentityProjectionService identityProjections =
            mock(ProjectFileIdentityProjectionService.class);
    private final ProjectFileProgressionProjectionService progressionProjections =
            mock(ProjectFileProgressionProjectionService.class);
    private final ProjectIntakeProgressionService intake = mock(ProjectIntakeProgressionService.class);
    private final RoleAssessmentService roleAssessments = mock(RoleAssessmentService.class);
    private final IdentityAssessmentService identityAssessments = mock(IdentityAssessmentService.class);
    private final ProjectFileRoleDecisionRepository roleDecisions = mock(ProjectFileRoleDecisionRepository.class);
    private final ProjectFileIdentityDecisionRepository identityDecisions =
            mock(ProjectFileIdentityDecisionRepository.class);
    private final ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
    private final ContractCandidateMaterializationService materialization =
            mock(ContractCandidateMaterializationService.class);
    private final ContractFactExtractionService factExtraction = mock(ContractFactExtractionService.class);
    private final ContractFactExtractionRepository factExtractions = mock(ContractFactExtractionRepository.class);
    private final ContractFactCandidatePort factCandidates = mock(ContractFactCandidatePort.class);
    private final ProjectFile file = mock(ProjectFile.class);
    private final com.fuquan.audit.contract.domain.project.Project project =
            mock(com.fuquan.audit.contract.domain.project.Project.class);
    private final ProjectFileRoleProjection role = mock(ProjectFileRoleProjection.class);
    private final EffectiveProjectIdentity identity = mock(EffectiveProjectIdentity.class);
    private final RoleAssessmentResult roleResult = mock(RoleAssessmentResult.class);
    private final com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment assessment =
            mock(com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment.class);
    private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    private final List<Object> audits = new ArrayList<>();
    private ProjectFileProgressionRecoveryService service;
    private ProjectFileProgressionRecoveryTransactionCoordinator transactions;

    @BeforeEach
    void setUp() {
        when(file.projectId()).thenReturn(PROJECT);
        when(file.id()).thenReturn(FILE);
        when(file.sha256()).thenReturn("a".repeat(64));
        when(project.id()).thenReturn(PROJECT);
        when(project.versionNo()).thenReturn(3L);
        when(projects.findById(PROJECT)).thenReturn(Optional.of(project));
        when(files.findByProjectIdAndId(PROJECT, FILE)).thenReturn(Optional.of(file));
        when(role.parseResultId()).thenReturn(UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(role.parseVersion()).thenReturn(2);
        when(role.effectiveRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(role.roleDecisionId()).thenReturn(ROLE_DECISION);
        ProjectFileIdentityDecision identityDecision = mock(ProjectFileIdentityDecision.class);
        when(identityDecision.decisionId()).thenReturn(IDENTITY_DECISION);
        when(identityDecision.projectId()).thenReturn(PROJECT);
        when(identityDecision.fileId()).thenReturn(FILE);
        when(identityDecision.fileSha256()).thenReturn("a".repeat(64));
        when(identityDecision.parseResultId()).thenReturn(
                UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(identityDecision.parseVersion()).thenReturn(2);
        when(identityDecision.resolution()).thenReturn(ProjectIdentityResolution.CONFIRM_SAME_PROJECT);
        when(identity.currentHumanSameDecision()).thenReturn(identityDecision);
        when(identityDecisions.findById(IDENTITY_DECISION)).thenReturn(Optional.of(identityDecision));
        ProjectFileRoleDecision roleDecision = mock(ProjectFileRoleDecision.class);
        when(roleDecision.projectId()).thenReturn(PROJECT);
        when(roleDecision.fileId()).thenReturn(FILE);
        when(roleDecision.fileSha256()).thenReturn("a".repeat(64));
        when(roleDecision.parseResultId()).thenReturn(
                UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(roleDecision.parseVersion()).thenReturn(2);
        when(roleDecision.selectedRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(roleDecisions.findById(ROLE_DECISION)).thenReturn(Optional.of(roleDecision));
        when(factCandidates.configuredProvenance()).thenReturn(FACT_PROVENANCE);
        when(roleProjections.project(file)).thenReturn(role);
        when(identityProjections.project(file)).thenReturn(identity);
        when(progressionProjections.project(file, role, identity)).thenReturn(rolePending("fp-current"));
        when(assessment.assessmentId()).thenReturn(ASSESSMENT);
        when(assessment.projectId()).thenReturn(PROJECT);
        when(assessment.fileId()).thenReturn(FILE);
        when(assessment.fileSha256()).thenReturn("a".repeat(64));
        when(assessment.parseResultId()).thenReturn(UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(assessment.parseVersion()).thenReturn(2);
        when(roleResult.assessment()).thenReturn(assessment);
        when(intake.ensureRoleAssessment(eq(PROJECT), eq(FILE), any(), any())).thenReturn(roleResult);
        when(roleAssessments.get(PROJECT, FILE, ASSESSMENT)).thenReturn(roleResult);
        AuthContext auth = () -> Optional.of(ACTOR);
        transactions = new ProjectFileProgressionRecoveryTransactionCoordinator(
                idempotency, event -> audits.add(event), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ProjectFileProgressionRecoveryService(files, projects, roleProjections, identityProjections,
                progressionProjections, intake, roleAssessments, identityAssessments, roleDecisions,
                identityDecisions, candidates, materialization, factExtraction, factExtractions,
                factCandidates,
                transactions, auth,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sameBlockerWithChangedInputFingerprintFailsClosedBeforeAnyStageCall() {
        var command = command("fp-old", "changed-input");

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);

        verify(intake, never()).ensureRoleAssessment(any(), any(), any(), any());
        assertThat(idempotency.size()).isZero();
    }

    @Test
    void progressionDiagnosticsHashTheClientOperationKey(CapturedOutput output) {
        String rawKey = "operation-key-canary-never-log";

        service.resume(command("fp-current", rawKey));

        assertThat(output).contains("event=PROGRESSION_PRECHECK")
                .contains("event=RECOVERY_TERMINAL")
                .contains("operationKeyHash=u")
                .doesNotContain(rawKey);
    }

    @Test
    void completedCommandReplaysAfterResponseLossEvenWhenCurrentBlockerAdvanced() {
        ProjectFileProgressionRecoveryResult first = service.resume(command("fp-current", "response-loss"));
        assertThat(first.replayed()).isFalse();

        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("NEEDS_ROLE_CONFIRMATION",
                        "NEEDS_ROLE_CONFIRMATION", List.of("CONFIRM_ROLE"), null, null));
        clearInvocations(intake);

        ProjectFileProgressionRecoveryResult replay = service.resume(command("fp-current", "response-loss"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.file()).isSameAs(file);
        verify(intake, never()).ensureRoleAssessment(any(), any(), any(), any());
        verify(roleAssessments, times(2)).get(PROJECT, FILE, ASSESSMENT);
    }

    @Test
    void sameActorAndKeyWithDifferentRequestIdentityIsRejected() {
        service.resume(command("fp-current", "same-key"));

        assertThatThrownBy(() -> service.resume(command("other-fingerprint", "same-key")))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.IDEMPOTENCY_REQUEST_MISMATCH);
    }

    @Test
    void concurrentSameKeyDoesNotExecuteTheSingleFileStageTwice() {
        var command = command("fp-current", "double-click");
        transactions.claim(command, ACTOR);

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_IN_PROGRESS);

        verify(intake, never()).ensureRoleAssessment(any(), any(), any(), any());
    }

    @Test
    void stageResultFromAChangedParseCannotCompleteTheAuthorizedRecovery() {
        when(assessment.parseVersion()).thenReturn(3);

        assertThatThrownBy(() -> service.resume(command("fp-current", "changed-during-stage")))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
    }

    @Test
    void factExtractionRecoveryInvokesOnlyTheRequestedFileAndPersistsItsExactAttempt() {
        UUID attemptId = UUID.fromString("33000000-0000-0000-0000-000000000099");
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenReturn(new ContractFactExtractionResult(attemptId, false, "SUCCEEDED", 2, 1));
        ContractFactExtractionAttempt attempt = mock(ContractFactExtractionAttempt.class);
        when(attempt.projectId()).thenReturn(PROJECT);
        when(attempt.fileId()).thenReturn(FILE);
        when(attempt.fileSha256()).thenReturn("a".repeat(64));
        when(attempt.parseResultId()).thenReturn(
                UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(attempt.parseVersion()).thenReturn(2);
        when(attempt.effectiveRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(attempt.roleDecisionId()).thenReturn(ROLE_DECISION);
        when(attempt.identityDecisionId()).thenReturn(IDENTITY_DECISION);
        when(attempt.actorId()).thenReturn(ACTOR.userId());
        when(attempt.operationKey()).thenReturn(
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-key",
                                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1")), ACTOR));
        when(attempt.provenance()).thenReturn(FACT_PROVENANCE);
        when(attempt.status()).thenReturn(ContractFactExtractionAttempt.Status.SUCCEEDED);
        when(factExtractions.findById(attemptId)).thenReturn(Optional.of(attempt));

        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-key",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        var result = service.resume(command);

        assertThat(result.replayed()).isFalse();
        verify(factExtraction).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());
        verify(intake, never()).ensureRoleAssessment(any(), any(), any(), any());

        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("READY_FOR_CONFIRMATION", null,
                        List.of(), null, null, null));
        assertThat(service.resume(command).replayed()).isTrue();
        verify(factExtraction, times(1)).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());
    }

    @Test
    void completedFactAttemptRecoversTheOriginalInProgressStageWithoutCallingProviderAgain() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-response-lost",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        transactions.claim(command, ACTOR);
        transactions.claimStage(command, ACTOR);

        UUID attemptId = UUID.fromString("33000000-0000-0000-0000-000000000097");
        ContractFactExtractionAttempt attempt = successfulFactAttempt(command, attemptId);
        when(factExtractions.findByOperation(ACTOR.userId(),
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR))).thenReturn(Optional.of(attempt));
        when(factExtractions.findById(attemptId)).thenReturn(Optional.of(attempt));

        ProjectFileProgressionRecoveryResult recovered = service.resume(command);

        assertThat(recovered.replayed()).isTrue();
        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status)
                .isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status)
                .isEqualTo(IdempotencyStatus.SUCCEEDED);
    }

    @Test
    void completedFactAttemptForAnotherOperationCannotRecoverTheOriginalStage() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-wrong-operation",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        transactions.claim(command, ACTOR);
        transactions.claimStage(command, ACTOR);
        UUID attemptId = UUID.fromString("33000000-0000-0000-0000-000000000096");
        ContractFactExtractionAttempt wrong = successfulFactAttempt(command, attemptId,
                "another-operation-key");
        when(factExtractions.findByOperation(eq(ACTOR.userId()), any())).thenReturn(Optional.of(wrong));
        when(factExtractions.findById(attemptId)).thenReturn(Optional.of(wrong));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void provenFailedFactAttemptClosesTheOriginalStageAndOuterOperationWithoutReexecution() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-proven-failed",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        transactions.claim(command, ACTOR);
        transactions.claimStage(command, ACTOR);

        ContractFactExtractionAttempt failed = factAttempt(command,
                UUID.fromString("33000000-0000-0000-0000-000000000095"),
                ContractFactExtractionAttempt.Status.FAILED, "INVALID_RESPONSE");
        when(factExtractions.findByOperation(ACTOR.userId(),
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR))).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);

        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void unknownFactAttemptKeepsTheOriginalOperationIdentityInProgress() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-unknown",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        transactions.claim(command, ACTOR);
        transactions.claimStage(command, ACTOR);

        ContractFactExtractionAttempt unknown = factAttempt(command,
                UUID.fromString("33000000-0000-0000-0000-000000000094"),
                ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN, "OUTCOME_UNKNOWN");
        when(factExtractions.findByOperation(ACTOR.userId(),
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR))).thenReturn(Optional.of(unknown));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void failedStageAndInProgressOuterReconcileFromTheSameProvenFailedInnerAttempt() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-failed-stage-gap",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        var outer = transactions.claim(command, ACTOR).record();
        var stage = transactions.claimStage(command, ACTOR).record();
        transactions.fail(stage, command, ACTOR);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);

        ContractFactExtractionAttempt failed = factAttempt(command,
                UUID.fromString("33000000-0000-0000-0000-000000000093"),
                ContractFactExtractionAttempt.Status.FAILED, "INVALID_RESPONSE");
        when(factExtractions.findByOperation(ACTOR.userId(),
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR))).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);
        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void factExtractionRecoveryRejectsACompletedAttemptWithAnotherInnerOperationIdentity() {
        UUID attemptId = UUID.fromString("33000000-0000-0000-0000-000000000098");
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenReturn(new ContractFactExtractionResult(attemptId, false, "SUCCEEDED", 1, 0));
        ContractFactExtractionAttempt attempt = mock(ContractFactExtractionAttempt.class);
        when(attempt.projectId()).thenReturn(PROJECT);
        when(attempt.fileId()).thenReturn(FILE);
        when(attempt.fileSha256()).thenReturn("a".repeat(64));
        when(attempt.parseResultId()).thenReturn(
                UUID.fromString("33000000-0000-0000-0000-000000000004"));
        when(attempt.parseVersion()).thenReturn(2);
        when(attempt.effectiveRole()).thenReturn(DocumentRole.CONTRACT_BODY);
        when(attempt.roleDecisionId()).thenReturn(ROLE_DECISION);
        when(attempt.identityDecisionId()).thenReturn(IDENTITY_DECISION);
        when(attempt.actorId()).thenReturn(ACTOR.userId());
        when(attempt.operationKey()).thenReturn("another-operation-key");
        when(attempt.provenance()).thenReturn(FACT_PROVENANCE);
        when(attempt.status()).thenReturn(ContractFactExtractionAttempt.Status.SUCCEEDED);
        when(factExtractions.findById(attemptId)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.resume(new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-key-other",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"))))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_INPUT_CHANGED);
    }

    @Test
    void provenFactProviderFailureIsRejectedAndSameKeyNeverReexecutes() {
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenThrow(new ContractFactCandidateException(
                        ContractFactCandidateException.Reason.INVALID_RESPONSE,
                        new IllegalStateException("invalid provider response")));
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-rejected",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);

        verify(factExtraction, times(1)).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());

        var explicitRetry = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-explicit-retry",
                new ProjectRequestMetadata("request-2", "trace-2", "127.0.0.1"));
        assertThatThrownBy(() -> service.resume(explicitRetry))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        verify(factExtraction, times(2)).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());
    }

    @Test
    void deterministicSegmentationFailureClosesBothClaimsAndSameKeyDoesNotReexecute() {
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenThrow(new ContractFactSegmentationException(7681, 33, 32, 240));
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-segment-budget",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.FACT_SEGMENT_BUDGET_EXCEEDED);
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.FAILED);

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        verify(factExtraction, times(1)).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());
    }

    @Test
    void unexpectedFactRuntimeFailureRetainsUnknownProtectionAndBothClaimsInProgress() {
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenThrow(new IllegalStateException("outcome cannot be proven"));
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "fact-unexpected-runtime",
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));

        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
        assertThat(transactions.findStage(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(transactions.find(command, ACTOR)).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void knownModelRejectionsWithAuditedRowsCloseAsBusinessFailureAfterResponseLoss() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "failed-audited-batch",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
        transactions.claim(command, ACTOR);
        transactions.claimStage(command, ACTOR);
        var failed = factAttempt(command, UUID.randomUUID(), ContractFactExtractionAttempt.Status.FAILED,
                "ALL_SCOPES_FAILED").completed(ContractFactExtractionAttempt.Status.FAILED, 0, 3,
                        "ALL_SCOPES_FAILED", NOW.plusSeconds(1));
        when(factExtractions.findByOperation(eq(ACTOR.userId()), any())).thenReturn(Optional.of(failed));
        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.REQUEST_REJECTED);
        assertThat(transactions.find(command, ACTOR).orElseThrow().status()).isEqualTo(IdempotencyStatus.FAILED);
        verify(factExtraction, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void returnedUnknownModelBatchRetainsClaimsAndReportsOutcomeUncertainty() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "returned-unknown-batch",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("FACT_EXTRACTION_PENDING", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        UUID attemptId = UUID.randomUUID();
        var unknown = factAttempt(command, attemptId, ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN,
                "MODEL_OUTCOME_UNKNOWN");
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenReturn(new ContractFactExtractionResult(attemptId, false, "OUTCOME_UNKNOWN", 0, 0));
        when(factExtractions.findById(attemptId)).thenReturn(Optional.of(unknown));
        when(factExtractions.findByOperation(eq(ACTOR.userId()), any())).thenReturn(Optional.of(unknown));
        for (int request = 0; request < 2; request++) {
            assertThatThrownBy(() -> service.resume(command))
                    .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                    .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                    .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
            assertThat(transactions.find(command, ACTOR).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
            assertThat(transactions.findStage(command, ACTOR).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        }
        verify(factExtraction, times(1)).extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any());
    }

    @Test
    void transportTimeoutDoesNotMakeTheOriginalOperationRetryableFailed() {
        var command = new ProjectFileProgressionRecoveryCommand(PROJECT, FILE,
                "FACT_EXTRACTION_PENDING", "fp-fact", "timeout-batch",
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
        when(progressionProjections.project(file, role, identity)).thenReturn(
                new ProjectFileProgressionProjection("FACT_EXTRACTION_PENDING", "FACT_EXTRACTION_PENDING",
                        List.of("RESUME_FACT_EXTRACTION"), null, null, "fp-fact"));
        when(factExtraction.extract(eq(PROJECT), eq(FILE), any(), eq(ACTOR), any()))
                .thenThrow(new ContractFactCandidateException(ContractFactCandidateException.Reason.TIMEOUT, null));
        assertThatThrownBy(() -> service.resume(command))
                .isInstanceOf(ProjectFileProgressionRecoveryException.class)
                .extracting(error -> ((ProjectFileProgressionRecoveryException) error).classification())
                .isEqualTo(ProjectFileProgressionRecoveryException.Classification.RECOVERY_OUTCOME_UNCERTAIN);
        assertThat(transactions.find(command, ACTOR).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(transactions.findStage(command, ACTOR).orElseThrow().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    private static ContractFactExtractionAttempt successfulFactAttempt(
            ProjectFileProgressionRecoveryCommand command, UUID attemptId) {
        return successfulFactAttempt(command, attemptId,
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR));
    }

    private static ContractFactExtractionAttempt successfulFactAttempt(
            ProjectFileProgressionRecoveryCommand command, UUID attemptId, String operationKey) {
        return factAttempt(command, attemptId, operationKey,
                ContractFactExtractionAttempt.Status.SUCCEEDED, null);
    }

    private static ContractFactExtractionAttempt factAttempt(
            ProjectFileProgressionRecoveryCommand command, UUID attemptId,
            ContractFactExtractionAttempt.Status status, String failureReason) {
        return factAttempt(command, attemptId,
                ProjectFileProgressionRecoveryTransactionCoordinator.innerKey(
                        "contract-fact", command, ACTOR), status, failureReason);
    }

    private static ContractFactExtractionAttempt factAttempt(
            ProjectFileProgressionRecoveryCommand command, UUID attemptId, String operationKey,
            ContractFactExtractionAttempt.Status status, String failureReason) {
        return new ContractFactExtractionAttempt(attemptId, PROJECT, FILE, "a".repeat(64),
                UUID.fromString("33000000-0000-0000-0000-000000000004"), 2,
                "b".repeat(64), DocumentRole.CONTRACT_BODY, ROLE_DECISION, IDENTITY_DECISION,
                command.expectedRecoveryInputFingerprint(), ACTOR.userId(), operationKey, FACT_PROVENANCE,
                status, 1, status == ContractFactExtractionAttempt.Status.SUCCEEDED ? 2 : 0,
                0, failureReason, NOW, NOW, 1);
    }

    private static ProjectFileProgressionRecoveryCommand command(String fingerprint, String key) {
        return new ProjectFileProgressionRecoveryCommand(PROJECT, FILE, "ROLE_ASSESSMENT_PENDING",
                fingerprint, key, new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
    }

    private static ProjectFileProgressionProjection rolePending(String fingerprint) {
        return new ProjectFileProgressionProjection("PARSED", "ROLE_ASSESSMENT_PENDING",
                List.of("RESUME_ROLE_ASSESSMENT"), null, null, fingerprint);
    }
}
