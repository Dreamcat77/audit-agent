package com.fuquan.audit.contract.application.reviewrun;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ContractReviewStepDiagnosticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private static final String HASH = "a".repeat(64);
    private final UUID projectId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final ReviewStepOutputReadService outputs = mock(ReviewStepOutputReadService.class);
    private final ContractModelCallAuditRepository calls = mock(ContractModelCallAuditRepository.class);
    private final ContractReviewStepDiagnosticsService service =
            new ContractReviewStepDiagnosticsService(outputs, calls);

    @Test
    void readsOrderedSafeAuditAndClassifiesTheLastFailedCall() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                call(2, CallPhase.REPAIR, CallOutcome.FAILED, "INVALID_TYPED_SCHEMA"),
                call(1, CallPhase.TRANSPORT, CallOutcome.SUCCEEDED, null)));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.failureStage()).isEqualTo(
                ContractReviewStepDiagnosticsService.FailureStage.TYPED_SCHEMA);
        assertThat(result.failureReasonCode()).isEqualTo("INVALID_TYPED_SCHEMA");
        assertThat(result.modelCalls()).extracting(
                ContractReviewStepDiagnosticsService.ModelCallDiagnostic::callOrdinal)
                .containsExactly(1, 2);
        assertThat(result.modelCalls().get(1).durationMs()).isEqualTo(12);
        assertThat(result.modelCalls().get(1).finishReason()).isEqualTo("STOP");
        assertThat(result.modelCalls().get(1).totalTokens()).isEqualTo(15);
    }

    @Test
    void reportsHistoricalCoarseFailureAsUnknownWhenNoCallAuditExists() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.failureStage()).isEqualTo(
                ContractReviewStepDiagnosticsService.FailureStage.UNKNOWN);
        assertThat(result.failureReasonCode()).isEqualTo("SEMANTIC_MODEL_INVALID_RESULT");
        assertThat(result.modelCalls()).isEmpty();
    }

    @Test
    void classifiesDurableSemanticEvidenceAndCorrectionReasonsWithoutMessageParsing() {
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.EVIDENCE_REFERENCE);
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "INVALID_CORRECTION_SCHEMA"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.TYPED_SCHEMA);
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "MISSING_REQUIRED_BREACH_CATEGORY"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.COMPLETENESS);
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "CORRECTION_REPAIR_TARGET_MISMATCH"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.EVIDENCE_REFERENCE);
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "CORRECTION_SEMANTIC_INVALID"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.BUSINESS_VALIDATION);
        assertThat(ContractReviewStepDiagnosticsService.classify(
                "CORRECTION_UNPROCESSABLE_RESULT"))
                .isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.BUSINESS_VALIDATION);
    }

    @Test
    void rejectsAuditRowsWhoseDurableIdentityDoesNotMatchTheCurrentAttempt() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        ContractModelCallAudit wrongOwner = new ContractModelCallAudit(UUID.randomUUID(), null,
                UUID.randomUUID(), 1, null, 1, CallPhase.TRANSPORT, HASH,
                "OLLAMA_PRIVATE", "approved-model", CallOutcome.FAILED,
                "READ_TIMEOUT", 12, null, null, null, null, null, NOW);
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(wrongOwner));

        assertThatThrownBy(() -> service.get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void hidesAnExactRunFromAnotherActorBeforeReadingCallAudit() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");

        assertThatThrownBy(() -> service.get(projectId, contractId, runId, stepId, "other"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(calls);
    }

    @Test
    void responseTypeHasNoRawOrSensitiveField() {
        assertThat(Arrays.stream(ContractReviewStepDiagnosticsService.Diagnostics.class
                        .getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("prompt", "rawResponse", "documentText", "authorization",
                        "cookie", "stackTrace", "serverPath");
        assertThat(Arrays.stream(ContractReviewStepDiagnosticsService.ModelCallDiagnostic.class
                        .getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("prompt", "rawResponse", "requestBody", "responseBody");
    }

    @Test
    void replacesAnUnrecognizedDurableFinishReasonWithUnknown() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        ContractModelCallAudit unsafe = new ContractModelCallAudit(UUID.randomUUID(), null,
                attemptId, 1, null, 1, CallPhase.TRANSPORT, HASH,
                "OLLAMA_PRIVATE", "approved-model", CallOutcome.FAILED,
                "INVALID_TYPED_SCHEMA", 12, "provider-private-detail", 5, 10, 15,
                null, NOW);
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(unsafe));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.modelCalls().get(0).finishReason()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "contract-semantic-work-v2-redaction-v1-grounding-v1",
            "contract-semantic-work-v3-redaction-v1-grounding-v1",
            "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1",
            "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1"})
    void reopensDurableLengthFailureWhoseCallFingerprintBelongsToTheUnitNotTheWholeAttempt(String policy) {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", policy);

        var result = assertDoesNotThrow(() -> fixture.service().get(
                projectId, contractId, runId, stepId, "owner"));

        assertThat(result.externalRequestFingerprint()).isEqualTo(HASH);
        assertThat(result.failureReasonCode()).isEqualTo("OUTPUT_TOKEN_LIMIT");
        assertThat(result.failureStage().name()).isEqualTo("PROVIDER_GENERATION");
        assertThat(result.modelCalls()).singleElement().satisfies(call -> {
            assertThat(call.outcome().name()).isEqualTo("FAILED");
            assertThat(call.finishReason()).isEqualTo("LENGTH");
            assertThat(call.durationMs()).isEqualTo(269055);
            assertThat(call.promptTokens()).isEqualTo(4006);
            assertThat(call.completionTokens()).isEqualTo(6144);
        });
    }

    @Test
    void checkpointCapacityFailureRetainsPreciseCodeAsLocalBusinessValidation() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        var result = fixture.service().get(projectId, contractId, runId, stepId, "owner");
        assertThat(result.failureReasonCode()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.stepSafeReasonCode()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.attemptSafeReasonCode()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.modelCalls().get(0).safeReason()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.failureStage()).isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.BUSINESS_VALIDATION);
    }

    @Test
    void terminalSynthesisCapacityFailureIsNotHiddenByAnEarlierRepairedEvidenceFailure() {
        givenStep("SEMANTIC_SYNTHESIS_INPUT_BOUND");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                call(1, CallPhase.TRANSPORT, CallOutcome.FAILED, "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"),
                call(2, CallPhase.REPAIR, CallOutcome.SUCCEEDED, null),
                call(3, CallPhase.TRANSPORT, CallOutcome.SUCCEEDED, null)));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.stepSafeReasonCode()).isEqualTo("SEMANTIC_SYNTHESIS_INPUT_BOUND");
        assertThat(result.attemptSafeReasonCode()).isEqualTo("SEMANTIC_SYNTHESIS_INPUT_BOUND");
        assertThat(result.failureReasonCode()).isEqualTo("SEMANTIC_SYNTHESIS_INPUT_BOUND");
        assertThat(result.failureStage()).isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.BUSINESS_VALIDATION);
        assertThat(result.modelCalls()).hasSize(3);
        assertThat(result.modelCalls().get(0).safeReason()).isEqualTo("MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE");
    }

    @Test
    void localCommittedBoundaryStopIsNotReportedAsARepairedProviderEvidenceError() {
        givenStep("SEMANTIC_LOCAL_BOUNDARY_STOPPED");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                call(1, CallPhase.TRANSPORT, CallOutcome.FAILED, "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"),
                call(2, CallPhase.REPAIR, CallOutcome.SUCCEEDED, null)));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.stepSafeReasonCode()).isEqualTo("SEMANTIC_LOCAL_BOUNDARY_STOPPED");
        assertThat(result.failureReasonCode()).isEqualTo("SEMANTIC_LOCAL_BOUNDARY_STOPPED");
        assertThat(result.failureStage()).isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.JAVA_VALIDATION);
        assertThat(result.modelCalls()).hasSize(2);
    }

    @Test
    void failedProviderProtocolRemainsPrimaryWhenItsFailureReceiptAlsoExceedsCheckpointCapacity() {
        givenStep("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                call(1, CallPhase.TRANSPORT, CallOutcome.FAILED, "MISSING_REQUIRED_BREACH_CATEGORY")));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.stepSafeReasonCode()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.attemptSafeReasonCode()).isEqualTo("SEMANTIC_CHECKPOINT_OUTPUT_BOUND");
        assertThat(result.failureReasonCode()).isEqualTo("MISSING_REQUIRED_BREACH_CATEGORY");
        assertThat(result.failureStage()).isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.COMPLETENESS);
        assertThat(result.modelCalls().get(0).safeReason()).isEqualTo("MISSING_REQUIRED_BREACH_CATEGORY");
    }

    @Test
    void safelyClassifiesOutputLimitWithoutCallingItJsonParseOrTransportFailure() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                call(1, CallPhase.TRANSPORT, CallOutcome.FAILED, "OUTPUT_TOKEN_LIMIT")));

        var result = service.get(projectId, contractId, runId, stepId, "owner");

        assertThat(result.failureReasonCode()).isEqualTo("OUTPUT_TOKEN_LIMIT");
        assertThat(result.modelCalls().get(0).safeReason()).isEqualTo("OUTPUT_TOKEN_LIMIT");
        assertThat(result.failureStage().name()).isEqualTo("PROVIDER_GENERATION");
    }

    @Test
    void exposesProvenUnknownTelemetryWithoutTurningItsPhysicalFailedAuditIntoKnownFailure() {
        var fixture = new DurableFixture(UnitStatus.UNKNOWN, "TRANSPORT_OUTCOME_UNKNOWN");

        var result = assertDoesNotThrow(() -> fixture.service().get(
                projectId, contractId, runId, stepId, "owner"));

        assertThat(result.externalCallState()).isEqualTo(ReviewStepExternalCallState.OUTCOME_UNKNOWN);
        assertThat(result.modelCalls()).singleElement().satisfies(call -> {
            assertThat(call.outcome().name()).isEqualTo("UNKNOWN");
            assertThat(call.safeReason()).isEqualTo("TRANSPORT_OUTCOME_UNKNOWN");
            assertThat(call.durationMs()).isEqualTo(269055);
        });
        assertThat(result.failureStage()).isEqualTo(ContractReviewStepDiagnosticsService.FailureStage.UNKNOWN);
    }

    @Test
    void readsSentWithoutAuditAsUnconfirmedRatherThanInventingACompletedCall() {
        var fixture = new DurableFixture(UnitStatus.SENT, null);
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());

        var result = assertDoesNotThrow(() -> fixture.service().get(
                projectId, contractId, runId, stepId, "owner"));

        assertThat(result.externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        assertThat(result.modelCalls()).isEmpty();
        assertThat(result.failureReasonCode()).isNull();
    }

    @Test
    void legacyPolicyStillRejectsADifferentCallFingerprintWithoutConsultingCheckpoints() {
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        givenStep("SEMANTIC_MODEL_INVALID_RESULT");

        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(fixture.checkpoints);
    }

    @Test
    void durablePolicyCannotFallBackToLegacyValidationWhenTheCheckpointReaderIsAbsent() {
        new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        assertThatThrownBy(() -> service.get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingPlanOrMissingTerminalAuditRatherThanHidingDurableCalls() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        when(fixture.checkpoints.findPlanByStep(stepId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        when(fixture.checkpoints.findPlanByStep(stepId)).thenReturn(Optional.of(fixture.plan));
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnitFingerprintAuditIdAndOrdinalMismatches() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        for (CallMetadata metadata : List.of(
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", "f".repeat(64)),
                new CallMetadata(UUID.randomUUID(), "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint))) {
            fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", attemptId, metadata, 1));
            assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                    .isInstanceOf(BusinessException.class);
        }
        fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 2));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDuplicateUnitsAndDuplicateAudits() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        fixture.units.add(fixture.units.get(0));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.units.remove(1);
        fixture.units.add(fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 2));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.units.remove(1);
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(
                fixture.audit("OUTPUT_TOKEN_LIMIT"), fixture.audit("OUTPUT_TOKEN_LIMIT")));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnitOwnedByAnUnrelatedAttempt() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", UUID.randomUUID(),
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAuditStateContradictionsWithoutPromotingOrDemotingTheCallOutcome() {
        var fixture = new DurableFixture(UnitStatus.SENT, null);
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.units.set(0, fixture.unit(UnitStatus.UNKNOWN, "OUTPUT_TOKEN_LIMIT", attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(fixture.audit("OUTPUT_TOKEN_LIMIT")));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "TRANSPORT_OUTCOME_UNKNOWN", attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(fixture.audit("TRANSPORT_OUTCOME_UNKNOWN")));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.units.set(0, fixture.unit(UnitStatus.VERIFIED, null, attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(fixture.audit(null)));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reusedVerifiedUnitStaysWithItsActualOldAttemptAndCannotFabricateANewAttemptCall() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        fixture.units.set(0, fixture.unit(UnitStatus.VERIFIED, null, attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        UUID nextId = UUID.randomUUID();
        var next = new ContractReviewStepAttempt(nextId, runId, stepId, 2, "f".repeat(64),
                ContractReviewStepStatus.FAILED, null, null, NOW, ReviewStepExternalCallState.NOT_STARTED,
                null, null, null, null, NOW, NOW, "SEMANTIC_TOTAL_BUDGET_EXHAUSTED", null, null, 1);
        var step = fixture.output.step();
        var retried = new ContractReviewStep(stepId, runId, step.stepType(), true,
                ContractReviewStepStatus.FAILED, step.stepPolicyVersion(), 2, null, null,
                "SEMANTIC_TOTAL_BUDGET_EXHAUSTED", NOW, NOW, 3);
        when(outputs.getStep(projectId, contractId, runId, stepId)).thenReturn(
                new ReviewStepOutputReadService.StepOutput(fixture.output.run(), retried,
                        List.of(fixture.output.attempts().get(0), next), null, List.of(), List.of(), List.of()));
        when(calls.findByReviewStepAttempt(nextId)).thenReturn(List.of());

        var result = assertDoesNotThrow(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"));
        assertThat(result.attemptId()).isEqualTo(nextId);
        assertThat(result.modelCalls()).isEmpty();

        when(calls.findByReviewStepAttempt(nextId)).thenReturn(List.of(new ContractModelCallAudit(
                fixture.auditId, null, nextId, 2, null, 1, CallPhase.TRANSPORT,
                fixture.operationFingerprint, "OLLAMA_PRIVATE", "approved-model", CallOutcome.SUCCEEDED,
                null, 269055, "STOP", 4006, 6144, 10150, null, NOW)));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownWithoutAProvenSafeReasonFailsClosedWithConflictNotAnUnexpectedServerError() {
        var fixture = new DurableFixture(UnitStatus.UNKNOWN, null);
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aPlanWhoseEntireUnitLedgerIsMissingCannotMasqueradeAsNoCalls() {
        var fixture = new DurableFixture(UnitStatus.SENT, null);
        fixture.units.clear();
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void durableDiagnosticsUseOnlyReadOperationsAndNeverRequireALiveLease() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        var prior = fixture.plan;
        when(fixture.checkpoints.findPlanByStep(stepId)).thenReturn(Optional.of(new Plan(prior.planId(),
                prior.runId(), prior.stepId(), prior.originalAttemptId(), prior.currentAttemptId(), prior.spec(),
                prior.owner(), prior.fence(), NOW.minusSeconds(1000), prior.status(), prior.usedCalls(),
                prior.reservedCalls(), prior.usedRepairs(), prior.reservedRepairs(), prior.usedMs(),
                prior.reservedMs(), prior.finalResultSha256(), prior.versionNo(), prior.createdAt(), prior.updatedAt())));

        assertDoesNotThrow(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"));

        verify(fixture.checkpoints).findPlanByStep(stepId);
        verify(fixture.checkpoints).findUnits(fixture.planId);
        verifyNoMoreInteractions(fixture.checkpoints);
    }

    @Test
    void refusesAPlanFromADifferentRunEvenWhenItsAuditAndUnitMatch() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        var prior = fixture.plan;
        when(fixture.checkpoints.findPlanByStep(stepId)).thenReturn(Optional.of(new Plan(prior.planId(),
                UUID.randomUUID(), prior.stepId(), prior.originalAttemptId(), prior.currentAttemptId(), prior.spec(),
                prior.owner(), prior.fence(), prior.leaseUntil(), prior.status(), prior.usedCalls(),
                prior.reservedCalls(), prior.usedRepairs(), prior.reservedRepairs(), prior.usedMs(),
                prior.reservedMs(), prior.finalResultSha256(), prior.versionNo(), prior.createdAt(), prior.updatedAt())));
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aRealCurrentDispatchStillRequiresTheAttemptsImmutableExternalIdentity() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        fixture.externalState(ReviewStepExternalCallState.COMPLETED, null);
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
        fixture.externalState(ReviewStepExternalCallState.NOT_STARTED, HASH);
        assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unconfirmedSentOrUnknownCannotContradictAnAttemptMarkedExternallyCompleted() {
        for (UnitStatus status : List.of(UnitStatus.SENT, UnitStatus.UNKNOWN)) {
            var fixture = new DurableFixture(status, status == UnitStatus.UNKNOWN ? "TRANSPORT_OUTCOME_UNKNOWN" : null);
            fixture.externalState(ReviewStepExternalCallState.COMPLETED, HASH);
            if (status == UnitStatus.SENT) when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());
            assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void aVerifiedUnitWithItsActualSuccessfulAuditRemainsSuccessful() {
        var fixture = new DurableFixture(UnitStatus.VERIFIED, null);
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(new ContractModelCallAudit(
                fixture.auditId, null, attemptId, 1, null, 1, CallPhase.TRANSPORT,
                fixture.operationFingerprint, "OLLAMA_PRIVATE", "approved-model", CallOutcome.SUCCEEDED,
                null, 269055, "STOP", 4006, 6144, 10150, null, NOW)));

        var result = assertDoesNotThrow(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"));
        assertThat(result.modelCalls()).singleElement().satisfies(call ->
                assertThat(call.outcome().name()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void rejectsProviderModelPhaseDurationAndReasonDisagreementWithTheMatchedUnit() {
        var fixture = new DurableFixture(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT");
        for (CallMetadata metadata : List.of(
                new CallMetadata(fixture.auditId, "different-provider", "approved-model", fixture.operationFingerprint),
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "different-model", fixture.operationFingerprint))) {
            fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", attemptId, metadata, 1));
            assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                    .isInstanceOf(BusinessException.class);
        }
        fixture.units.set(0, fixture.unit(UnitStatus.FAILED, "OUTPUT_TOKEN_LIMIT", attemptId,
                new CallMetadata(fixture.auditId, "OLLAMA_PRIVATE", "approved-model", fixture.operationFingerprint), 1));
        for (ContractModelCallAudit mismatched : List.of(
                new ContractModelCallAudit(fixture.auditId, null, attemptId, 1, null, 1, CallPhase.REPAIR,
                        fixture.operationFingerprint, "OLLAMA_PRIVATE", "approved-model", CallOutcome.FAILED,
                        "OUTPUT_TOKEN_LIMIT", 269055, "LENGTH", 4006, 6144, 10150, null, NOW),
                new ContractModelCallAudit(fixture.auditId, null, attemptId, 1, null, 1, CallPhase.TRANSPORT,
                        fixture.operationFingerprint, "OLLAMA_PRIVATE", "approved-model", CallOutcome.FAILED,
                        "OUTPUT_TOKEN_LIMIT", 269056, "LENGTH", 4006, 6144, 10150, null, NOW),
                fixture.audit("INVALID_PROVIDER_JSON"))) {
            when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(mismatched));
            assertThatThrownBy(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"F02_DETERMINISTIC", "BREACH", "TERM"})
    void otherStepsOfADurablePolicyRunDoNotRequireOrReadTheSemanticLedger(ContractReviewStepType type) {
        var fixture = new DurableFixture(UnitStatus.FAILED, "SEMANTIC_MODEL_INVALID_RESULT");
        var prior = fixture.output.step();
        var step = new ContractReviewStep(stepId, runId, type, prior.required(), prior.status(),
                "deterministic-policy-v2", prior.currentAttemptNo(), null, null, null,
                prior.startedAt(), prior.completedAt(), prior.versionNo());
        when(outputs.getStep(projectId, contractId, runId, stepId)).thenReturn(
                new ReviewStepOutputReadService.StepOutput(fixture.output.run(), step,
                        fixture.output.attempts(), null, List.of(), List.of(), List.of()));
        when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of());

        var result = assertDoesNotThrow(() -> service.get(projectId, contractId, runId, stepId, "owner"));
        assertThat(result.stepType()).isEqualTo(type);
        assertThat(result.modelCalls()).isEmpty();
        assertDoesNotThrow(() -> fixture.service().get(projectId, contractId, runId, stepId, "owner"));
        verifyNoInteractions(fixture.checkpoints);
    }

    private final class DurableFixture {
        final SemanticCheckpointRepository checkpoints = mock(SemanticCheckpointRepository.class);
        final UUID planId = UUID.randomUUID();
        final UUID auditId = UUID.randomUUID();
        final String operationFingerprint = "b".repeat(64);
        final List<Unit> units = new ArrayList<>();
        final Plan plan;
        final ReviewStepOutputReadService.StepOutput output;

        DurableFixture(UnitStatus unitStatus, String reason) {
            this(unitStatus, reason, ContractReviewExecutionPolicyResolver.DURABLE_MODEL_POLICY_VERSION);
        }

        DurableFixture(UnitStatus unitStatus, String reason, String policy) {
            ContractReviewStepStatus status = unitStatus == UnitStatus.SENT ? ContractReviewStepStatus.RUNNING
                    : unitStatus == UnitStatus.UNKNOWN ? ContractReviewStepStatus.UNKNOWN : ContractReviewStepStatus.FAILED;
            ReviewStepExternalCallState external = unitStatus == UnitStatus.SENT ? ReviewStepExternalCallState.REQUESTED
                    : unitStatus == UnitStatus.UNKNOWN ? ReviewStepExternalCallState.OUTCOME_UNKNOWN
                    : ReviewStepExternalCallState.COMPLETED;
            ContractReviewRun run = new ContractReviewRun(runId, projectId, contractId, 1,
                    null, null, 1, "{}", HASH, "review-v1", "rule-v1", policy,
                    HASH, "owner", unitStatus == UnitStatus.SENT ? ContractReviewRunStatus.RUNNING
                            : ContractReviewRunStatus.FAILED, NOW.minusSeconds(5), NOW.minusSeconds(4),
                    null, unitStatus == UnitStatus.SENT ? null : NOW, null, reason, 2);
            ContractReviewStep step = new ContractReviewStep(stepId, runId, ContractReviewStepType.SEMANTIC_AI,
                    true, status, policy, 1, null, null, reason, NOW.minusSeconds(4),
                    unitStatus == UnitStatus.SENT ? null : NOW, 2);
            ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId, runId, stepId,
                    1, HASH, status, "worker", NOW.plusSeconds(300), NOW.minusSeconds(4), external,
                    HASH, null, "OLLAMA_PRIVATE", "approved-model", NOW.minusSeconds(4),
                    unitStatus == UnitStatus.SENT ? null : NOW, reason, null, null, 2);
            output = new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt), null,
                    List.of(), List.of(), List.of());
            when(outputs.getStep(projectId, contractId, runId, stepId)).thenReturn(output);
            plan = new Plan(planId, runId, stepId, attemptId, attemptId,
                    new PlanSpec(HASH, "c".repeat(64), "{}", NOW.plusSeconds(900), 900000, 11, 2),
                    "worker", 1, NOW.plusSeconds(300), PlanStatus.ACTIVE,
                    unitStatus == UnitStatus.FAILED ? 1 : 0, unitStatus == UnitStatus.FAILED ? 0 : 1,
                    0, 0, unitStatus == UnitStatus.FAILED ? 269055 : 0,
                    unitStatus == UnitStatus.FAILED ? 0 : 300000, null, 1, NOW.minusSeconds(4), NOW);
            units.add(unit(unitStatus, reason, attemptId,
                    new CallMetadata(auditId, "OLLAMA_PRIVATE", "approved-model", operationFingerprint), 1));
            when(checkpoints.findPlanByStep(stepId)).thenReturn(Optional.of(plan));
            when(checkpoints.findUnits(planId)).thenAnswer(invocation -> List.copyOf(units));
            when(calls.findByReviewStepAttempt(attemptId)).thenReturn(List.of(audit(reason)));
        }

        Unit unit(UnitStatus status, String reason, UUID ownerAttempt, CallMetadata metadata, int ordinal) {
            String result = status == UnitStatus.FAILED || status == UnitStatus.VERIFIED
                    || status == UnitStatus.REPAIR_REQUIRED ? "{}" : null;
            return new Unit(UUID.randomUUID(), planId, "d".repeat(64), 1, 1, Purpose.ANALYZE,
                    ownerAttempt, "e".repeat(64), List.of(), status, "worker", 1, NOW.plusSeconds(300),
                    300000, status == UnitStatus.SENT ? 0 : 269055, result,
                    result == null ? null : digest(result), reason, metadata, ordinal, 1, NOW.minusSeconds(4), NOW);
        }

        ContractModelCallAudit audit(String reason) {
            return new ContractModelCallAudit(auditId, null, attemptId, 1, null, 1,
                    CallPhase.TRANSPORT, operationFingerprint, "OLLAMA_PRIVATE", "approved-model",
                    CallOutcome.FAILED, reason, 269055, "LENGTH", 4006, 6144, 10150, null, NOW);
        }

        void externalState(ReviewStepExternalCallState external, String fingerprint) {
            var prior = output.attempts().get(0);
            var changed = new ContractReviewStepAttempt(prior.attemptId(), prior.runId(), prior.stepId(),
                    prior.attemptNo(), prior.operationIdentity(), prior.status(), prior.leaseOwner(), prior.leaseUntil(),
                    prior.claimedAt(), external, fingerprint, prior.providerRequestId(), prior.provider(), prior.model(),
                    prior.startedAt(), prior.completedAt(), prior.safeReasonCode(), prior.outputResourceType(),
                    prior.outputResourceId(), prior.versionNo());
            when(outputs.getStep(projectId, contractId, runId, stepId)).thenReturn(
                    new ReviewStepOutputReadService.StepOutput(output.run(), output.step(), List.of(changed),
                            null, List.of(), List.of(), List.of()));
        }

        ContractReviewStepDiagnosticsService service() {
            var factory = new DefaultListableBeanFactory();
            factory.registerSingleton("outputs", outputs);
            factory.registerSingleton("modelCalls", calls);
            factory.registerSingleton("checkpoints", checkpoints);
            return (ContractReviewStepDiagnosticsService) factory.autowire(
                    ContractReviewStepDiagnosticsService.class, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
        }
    }

    private void givenStep(String reason) {
        ContractReviewRun run = new ContractReviewRun(runId, projectId, contractId, 1,
                null, null, 1, "{}", HASH, "review-v1", "rule-v1", "model-v1",
                HASH, "owner", ContractReviewRunStatus.FAILED, NOW.minusSeconds(5),
                NOW.minusSeconds(4), null, NOW, null, reason, 2);
        ContractReviewStep step = new ContractReviewStep(stepId, runId,
                ContractReviewStepType.SEMANTIC_AI, true, ContractReviewStepStatus.FAILED,
                "semantic-v1", 1, null, null, reason, NOW.minusSeconds(4), NOW, 2);
        ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId, runId,
                stepId, 1, HASH, ContractReviewStepStatus.FAILED, "worker", NOW,
                NOW.minusSeconds(4), ReviewStepExternalCallState.COMPLETED, HASH, null,
                "OLLAMA_PRIVATE", "approved-model", NOW.minusSeconds(4), NOW, reason,
                null, null, 2);
        when(outputs.getStep(projectId, contractId, runId, stepId)).thenReturn(
                new ReviewStepOutputReadService.StepOutput(run, step, List.of(attempt), null,
                        List.of(), List.of(), List.of()));
    }

    private ContractModelCallAudit call(int ordinal, CallPhase phase, CallOutcome outcome,
            String safeReason) {
        return new ContractModelCallAudit(UUID.randomUUID(), null, attemptId, 1, null,
                ordinal, phase, HASH, "OLLAMA_PRIVATE", "approved-model", outcome,
                safeReason, 12, "STOP", 5, 10, 15, null, NOW);
    }
}
