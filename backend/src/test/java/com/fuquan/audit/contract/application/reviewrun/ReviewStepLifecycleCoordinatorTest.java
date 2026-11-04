package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewResultsRepositories;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractModelCallAuditRepository;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ReviewStepLifecycleCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-16T01:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser(
            "user-1", "auditor", "Audit User", Set.of("AUDITOR"));

    @Test
    void f02PublicationRetainsValidatedOriginalEvidenceWithAnExplicitSourceAttention() {
        String attention = "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE";
        var fixture = f02SourceFixture();
        var started = fixture.start().start(fixture.command("source-attention"), ACTOR);
        var result = f02SourceResult(fixture, started, attention);

        var completed = fixture.complete().complete(started, result);

        assertThat(completed.step().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(completed.findings()).singleElement().satisfies(value -> {
            assertThat(value.statusCode()).isEqualTo("PASS");
            assertThat(value.attentionCode()).isEqualTo(attention);
        });
        assertThat(completed.evidence()).singleElement()
                .satisfies(value -> assertThat(value.boundedExcerpt()).isEqualTo("original amount 12"));
    }

    @Test void f02OriginalValueEvidenceCannotUseTheLegacyValidationPolicy() {
        var fixture = f02SourceFixture();
        var started = fixture.start().start(fixture.command("legacy-source-policy"), ACTOR);
        var result = f02SourceResult(fixture, started, "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
        var source = result.evidence().get(0);
        var legacy = new ContractReviewEvidence(source.evidenceId(), source.findingId(), source.runId(), source.stepId(),
                source.attemptId(), source.ordinal(), source.fileId(), source.fileSha256(), source.parseResultId(),
                source.parseVersion(), source.canonicalTextSha256(), source.evidenceRole(), source.validationStatus(),
                "f02-frozen-evidence-v1", null, null, null, null, null, null, true, 0, 18,
                source.excerptSha256(), source.boundedExcerpt(), 1, NOW, 0);
        var invalid = new StepComputationResult(result.outputResourceType(), result.outputResourceId(),
                result.findings(), List.of(legacy), null, null, null);
        assertThatThrownBy(() -> fixture.complete().complete(started, invalid)).isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
    }

    @Test void f02OriginalValueEvidenceCannotBorrowAnotherFindingsOwnership() {
        var fixture = f02SourceFixture();
        var started = fixture.start().start(fixture.command("wrong-source-owner"), ACTOR);
        var result = f02SourceResult(fixture, started, "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
        var source = result.evidence().get(0);
        var foreign = new ContractReviewEvidence(source.evidenceId(), UUID.randomUUID(), source.runId(), source.stepId(),
                source.attemptId(), source.ordinal(), source.fileId(), source.fileSha256(), source.parseResultId(),
                source.parseVersion(), source.canonicalTextSha256(), source.evidenceRole(), source.validationStatus(),
                source.validationPolicyVersion(), null, null, null, null, null, null, true, 0, 18,
                source.excerptSha256(), source.boundedExcerpt(), 1, NOW, 0);
        var invalid = new StepComputationResult(result.outputResourceType(), result.outputResourceId(),
                result.findings(), List.of(foreign), null, null, null);
        assertThatThrownBy(() -> fixture.complete().complete(started, invalid)).isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
    }

    @Test void f02OriginalValuePublicationRejectsLateDuplicateCompletionWithoutReplacingEvidence() {
        var fixture = f02SourceFixture();
        var started = fixture.start().start(fixture.command("late-source-publication"), ACTOR);
        var result = f02SourceResult(fixture, started, "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
        fixture.complete().complete(started, result);
        assertThatThrownBy(() -> fixture.complete().complete(started, result)).isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findEvidenceByAttemptId(started.attempt().attemptId())).containsExactlyElementsOf(result.evidence());
    }

    @Test void f02PublicationDoesNotAllowAnArbitraryAttentionToBypassEvidenceValidation() {
        var fixture = f02SourceFixture();
        var started = fixture.start().start(fixture.command("bad-source-attention"), ACTOR);
        assertThatThrownBy(() -> fixture.complete().complete(started,
                f02SourceResult(fixture, started, "SOURCE_UNAVAILABLE:UNPROVEN")))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
    }

    private static StepComputationResult f02SourceResult(Fixture fixture,
            ReviewStepStartTransactionCoordinator.StartedExecution started, String attention) {
        var finding = new ContractReviewFinding(UUID.randomUUID(), fixture.runId(), started.step().stepId(),
                started.attempt().attemptId(), 1, FrozenF02ResultMapper.FINDING_TYPE, "F02_LINE_SUM_MATCH",
                "PASS", attention, "GREEN", "confirmed amount 14", null, 2, started.step().stepPolicyVersion(),
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null, NOW, 0);
        var material = fixture.material();
        var source = new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(), fixture.runId(),
                started.step().stepId(), started.attempt().attemptId(), 1, material.fileId(), material.fileSha256(),
                material.parseResultId(), material.parseVersion(), material.canonicalTextSha256(), "COMPARISON",
                "VALIDATED", FrozenF02ResultMapper.VALIDATION_POLICY_VERSION, null, null, null, null, null, null,
                true, 0, 18, "d".repeat(64), "original amount 12", 1, NOW, 0);
        return new StepComputationResult(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, UUID.randomUUID(),
                List.of(finding), List.of(source), null, null, null);
    }

    private static Fixture f02SourceFixture() {
        var rules = List.of(new ContractRuleConfiguration("F02_LINE_SUM_MATCH", "sum", true,
                RiskLevel.YELLOW, false, 1, "sum", NOW));
        return fixture(ContractReviewStepType.F02_DETERMINISTIC,
                new F02RulePolicyFingerprint().fingerprint(rules), rules);
    }

    @Test
    void startClaimsExactlyOneFirstAttemptAndCarriesImmutableExecutionContext() throws Exception {
        var fixture = fixture(ContractReviewStepType.SEMANTIC_AI);
        var command = fixture.command("execute-key");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> start(fixture.start(), command, gate));
            var second = pool.submit(() -> start(fixture.start(), command, gate));
            gate.countDown();
            var results = java.util.Arrays.asList(first.get(), second.get());
            assertThat(results.stream().filter(value -> value != null).count()).isEqualTo(1);
            var started = results.stream().filter(value -> value != null).findFirst().orElseThrow();
            assertThat(started.attempt().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            assertThat(started.step().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
            assertThat(started.run().status()).isEqualTo(ContractReviewRunStatus.RUNNING);
            assertThat(started.f02Configurations()).isEmpty();
            assertThat(started.actor()).isEqualTo(ACTOR);
            assertThat(started.command()).isEqualTo(command);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixture.idempotency().size()).isEqualTo(1);
        assertThat(fixture.audits()).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_REVIEW_STEP_STARTED");
    }

    @Test
    void f02PolicyMustMatchFrozenRunBeforeClaimAndExactConfigurationsEnterContext() {
        var configuration = new ContractRuleConfiguration("F02_A", "Rule A", true,
                RiskLevel.RED, true, 3, "description", NOW);
        var fingerprint = new F02RulePolicyFingerprint();
        String ruleVersion = fingerprint.fingerprint(List.of(configuration));
        var fixture = fixture(ContractReviewStepType.F02_DETERMINISTIC, ruleVersion,
                List.of(configuration));
        var started = fixture.start().start(fixture.command("f02-key"), ACTOR);
        assertThat(started.f02Configurations()).containsExactly(configuration);

        var drifted = fixture(ContractReviewStepType.F02_DETERMINISTIC, ruleVersion,
                List.of(new ContractRuleConfiguration("F02_A", "Rule A", true,
                        RiskLevel.YELLOW, true, 3, "description", NOW)));
        assertThatThrownBy(() -> drifted.start().start(drifted.command("drift-key"), ACTOR))
                .isInstanceOf(BusinessException.class);
        assertThat(drifted.store().findStepById(drifted.stepId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.PENDING);
        assertThat(drifted.idempotency().size()).isZero();
    }

    @Test
    void externalRequestedMarkerCommitsVersionedFingerprintWithoutGivingExecutorRepositories() {
        var fixture = fixture(ContractReviewStepType.SEMANTIC_AI);
        var started = fixture.start().start(fixture.command("semantic-key"), ACTOR);
        ReviewExecutionContext context = started.context(fixture.externalRequests());
        context.markExternalRequested("e".repeat(64));

        var durable = fixture.store().findAttemptById(started.attempt().attemptId()).orElseThrow();
        assertThat(durable.externalCallState()).isEqualTo(ReviewStepExternalCallState.REQUESTED);
        assertThat(durable.externalRequestFingerprint()).isEqualTo("e".repeat(64));
        assertThat(durable.versionNo()).isEqualTo(2);
        assertThat(context.startedAttempt()).isEqualTo(started.attempt());
        assertThatThrownBy(() -> context.markExternalRequested("f".repeat(64)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void completePublishesResultsAndTerminalStateAndMarksIdempotencySucceeded() {
        var fixture = fixture(ContractReviewStepType.BREACH);
        var started = fixture.start().start(fixture.command("complete-key"), ACTOR);
        var finding = finding(fixture, started, 1);
        var evidence = evidence(fixture, started, finding, fixture.material().parseResultId());

        var completed = fixture.complete().complete(started,
                new StepComputationResult("REVIEW_FINDING_SET", started.attempt().attemptId(),
                        List.of(finding), List.of(evidence), null, null, null));

        assertThat(completed.step().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(completed.attempt().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(completed.attempt().externalCallState())
                .isEqualTo(ReviewStepExternalCallState.NOT_STARTED);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId()))
                .containsExactly(finding);
        assertThat(fixture.results().findEvidenceByAttemptId(started.attempt().attemptId()))
                .containsExactly(evidence);
        assertThat(fixture.idempotency().findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, "complete-key").orElseThrow().status())
                .isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(completed.run().status()).isEqualTo(ContractReviewRunStatus.RUNNING);
    }

    @Test
    void differentStepsCompletingConcurrentlySerializeOnTheDurableRunRow() throws Exception {
        var fixture = fixture(ContractReviewStepType.BREACH);
        var breach = fixture.start().start(fixture.command("breach-complete"), ACTOR);
        UUID termStepId = fixture.store().findStepsByRunId(fixture.runId()).stream()
                .filter(value -> value.stepType() == ContractReviewStepType.TERM)
                .findFirst().orElseThrow().stepId();
        var term = fixture.start().start(fixture.command(termStepId, "term-complete"), ACTOR);
        var runRows = new AdversarialRunRowRepository(fixture.store());
        var first = completeCoordinator(fixture, runRows);
        var second = completeCoordinator(fixture, runRows);
        var breachFinding = finding(fixture, breach, 1);
        var termFinding = finding(fixture, term, 1);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var breachFuture = pool.submit(() -> {
                gate.await();
                return first.complete(breach, result(breach, breachFinding,
                        evidence(fixture, breach, breachFinding, fixture.material().parseResultId())));
            });
            var termFuture = pool.submit(() -> {
                gate.await();
                return second.complete(term, result(term, termFinding,
                        evidence(fixture, term, termFinding, fixture.material().parseResultId())));
            });
            gate.countDown();
            assertThat(List.of(breachFuture.get(), termFuture.get()))
                    .extracting(value -> value.step().status())
                    .containsOnly(ContractReviewStepStatus.COMPLETED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixture.store().findStepById(breach.step().stepId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(fixture.store().findStepById(term.step().stepId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(fixture.results().findFindingsByRunId(fixture.runId())).hasSize(2);
        assertThat(runRows.lockReads()).isEqualTo(2);
    }

    @Test
    void unknownAggregateDoesNotStrandOtherStepsThatWereAlreadyRunning() {
        var fixture = fixture(ContractReviewStepType.SEMANTIC_AI);
        var semantic = fixture.start().start(fixture.command("semantic-unknown"), ACTOR);
        UUID breachStepId = stepId(fixture, ContractReviewStepType.BREACH);
        UUID termStepId = stepId(fixture, ContractReviewStepType.TERM);
        var breach = fixture.start().start(fixture.command(breachStepId, "breach-after-unknown"), ACTOR);
        var term = fixture.start().start(fixture.command(termStepId, "term-after-unknown"), ACTOR);
        semantic.context(fixture.externalRequests()).markExternalRequested("e".repeat(64));

        fixture.unknown().unknown(semantic, "SEMANTIC_OUTCOME_UNKNOWN");
        var frozenUnknown = fixture.store().findById(fixture.runId()).orElseThrow();
        var breachFinding = finding(fixture, breach, 1);
        fixture.complete().complete(breach, result(breach, breachFinding,
                evidence(fixture, breach, breachFinding, fixture.material().parseResultId())));
        fixture.failure().fail(term, "TERM_DEFINITIVE_FAILURE");

        assertThat(fixture.store().findStepById(breachStepId).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(fixture.store().findStepById(termStepId).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.FAILED);
        assertThat(fixture.store().findById(fixture.runId()).orElseThrow()).satisfies(run -> {
            assertThat(run.status()).isEqualTo(ContractReviewRunStatus.UNKNOWN);
            assertThat(run.unknownAt()).isEqualTo(frozenUnknown.unknownAt());
            assertThat(run.safeReasonCode()).isEqualTo("SEMANTIC_OUTCOME_UNKNOWN");
        });
        assertThat(fixture.idempotency().findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE,
                "breach-after-unknown").orElseThrow().status())
                .isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(fixture.idempotency().findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE,
                "term-after-unknown").orElseThrow().status())
                .isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void invalidSecondEvidenceCannotPartiallyPublishFirstFindingOrEvidence() {
        var fixture = fixture(ContractReviewStepType.BREACH);
        var started = fixture.start().start(fixture.command("atomic-key"), ACTOR);
        var finding = finding(fixture, started, 1);
        var valid = evidence(fixture, started, finding, fixture.material().parseResultId());
        var invalid = evidence(fixture, started, finding, UUID.randomUUID());

        assertThatThrownBy(() -> fixture.complete().complete(started,
                new StepComputationResult("REVIEW_FINDING_SET", started.attempt().attemptId(),
                        List.of(finding), List.of(valid, invalid), null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(fixture.results().findEvidenceByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(fixture.store().findAttemptById(started.attempt().attemptId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    @Test
    void typedSideEvidenceMustBelongToTheSameFindingAndSourceFile() {
        var fixture = fixture(ContractReviewStepType.BREACH);
        var started = fixture.start().start(fixture.command("typed-owner-key"), ACTOR);
        var base = finding(fixture, started, 1);
        var finding = new ContractReviewFinding(base.findingId(), base.runId(), base.stepId(),
                base.attemptId(), base.ordinal(), "BREACH_COMPARISON", "BREACH", "REVIEW",
                null, null, base.summary(), null, 2, base.policyVersion(), base.source(),
                null, null, null, null, null, NOW, 0);
        var evidence = evidence(fixture, started, finding, fixture.material().parseResultId());
        UUID comparisonId = UUID.randomUUID();
        var comparison = new ContractReviewClauseComparison(comparisonId, finding.findingId(),
                fixture.runId(), started.step().stepId(), started.attempt().attemptId(),
                "BREACH:SUPPLIER_DELAY", "BREACH", "NOT_EVALUATED", 2, List.of(
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, fixture.runId(),
                                "CONTRACT_BODY:wrong-file", UUID.randomUUID(), "KNOWN",
                                "SUPPLIER_DELAY", "PARTY_B", "CONTRACT_AMOUNT", null,
                                null, null, null, null, null, null, null, null, null, null,
                                null, null, List.of(evidence.evidenceId())),
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, fixture.runId(),
                                "TENDER_DOCUMENT:missing", UUID.randomUUID(), "NOT_EXTRACTED",
                                "SUPPLIER_DELAY", null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null,
                                List.of())));
        var typedComplete = new ReviewStepCompleteTransactionCoordinator(fixture.store(), fixture.store(),
                fixture.store(), fixture.store(), fixture.results(), fixture.results(), fixture.results(),
                fixture.idempotency(), ignored -> { }, new ReviewRunStatusAggregator(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> typedComplete.complete(started,
                new StepComputationResult("REVIEW_FINDING_SET", started.attempt().attemptId(),
                        List.of(finding), List.of(evidence), List.of(comparison), null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.results().findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
    }

    @Test
    void semanticCompletionRequiresDurableRequestedFingerprintBeforeModelCallOutcome() {
        var fixture = fixture(ContractReviewStepType.SEMANTIC_AI);
        var started = fixture.start().start(fixture.command("semantic-complete-key"), ACTOR);
        var result = new StepComputationResult("REVIEW_FINDING_SET", started.attempt().attemptId(),
                List.of(), List.of(), "provider-request-1", "provider-a", "model-a");

        assertThatThrownBy(() -> fixture.complete().complete(started, result))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.store().findAttemptById(started.attempt().attemptId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.RUNNING);

        started.context(fixture.externalRequests()).markExternalRequested("e".repeat(64));
        var completed = fixture.complete().complete(started, result);
        assertThat(completed.attempt().externalCallState())
                .isEqualTo(ReviewStepExternalCallState.COMPLETED);
        assertThat(completed.attempt().externalRequestFingerprint()).isEqualTo("e".repeat(64));
    }

    @Test
    void semanticSuccessAndFailurePersistModelCallsWithTheirExactAttempt() {
        var succeeded = fixture(ContractReviewStepType.SEMANTIC_AI);
        var successStart = succeeded.start().start(succeeded.command("semantic-audit-success"), ACTOR);
        successStart.context(succeeded.externalRequests()).markExternalRequested("e".repeat(64));
        var successCalls = new InMemoryContractModelCallAuditRepository();
        var successCoordinator = new ReviewStepCompleteTransactionCoordinator(
                succeeded.store(), succeeded.store(), succeeded.store(), succeeded.store(),
                succeeded.results(), succeeded.results(), null, succeeded.idempotency(), successCalls,
                ignored -> { }, new ReviewRunStatusAggregator(), Clock.fixed(NOW, ZoneOffset.UTC));
        var successCall = modelCall(successStart, "e".repeat(64), CallOutcome.SUCCEEDED, null);

        successCoordinator.complete(successStart, new StepComputationResult(
                "REVIEW_FINDING_SET", successStart.attempt().attemptId(), List.of(), List.of(),
                List.of(), List.of(successCall), null, null, "qwen-test"));

        assertThat(successCalls.findByReviewStepAttempt(successStart.attempt().attemptId()))
                .containsExactly(successCall);

        var failed = fixture(ContractReviewStepType.SEMANTIC_AI);
        var failedStart = failed.start().start(failed.command("semantic-audit-failure"), ACTOR);
        failedStart.context(failed.externalRequests()).markExternalRequested("f".repeat(64));
        var failedCalls = new InMemoryContractModelCallAuditRepository();
        var failureCoordinator = new ReviewStepFailureTransactionCoordinator(
                failed.store(), failed.store(), failed.store(), failedCalls, failed.idempotency(),
                ignored -> { }, new ReviewRunStatusAggregator(), Clock.fixed(NOW, ZoneOffset.UTC));
        var failedCall = modelCall(failedStart, "f".repeat(64), CallOutcome.FAILED,
                "INVALID_PROVIDER_JSON");

        failureCoordinator.fail(failedStart, "SEMANTIC_MODEL_INVALID_RESULT", List.of(failedCall));

        assertThat(failedCalls.findByReviewStepAttempt(failedStart.attempt().attemptId()))
                .containsExactly(failedCall);
    }

    @Test
    void nonSemanticFailureRejectsSemanticModelCallAudit() {
        var fixture = fixture(ContractReviewStepType.TERM);
        var started = fixture.start().start(fixture.command("term-audit-rejected"), ACTOR);
        started.context(fixture.externalRequests()).markExternalRequested("f".repeat(64));
        var calls = new InMemoryContractModelCallAuditRepository();
        var coordinator = new ReviewStepFailureTransactionCoordinator(
                fixture.store(), fixture.store(), fixture.store(), calls, fixture.idempotency(),
                ignored -> { }, new ReviewRunStatusAggregator(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> coordinator.fail(started, "TERM_FAILED",
                List.of(modelCall(started, "f".repeat(64), CallOutcome.FAILED, "INVALID_PROVIDER_JSON"))))
                .isInstanceOf(BusinessException.class);
        assertThat(calls.findByReviewStepAttempt(started.attempt().attemptId())).isEmpty();
        assertThat(fixture.store().findAttemptById(started.attempt().attemptId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    private static ContractModelCallAudit modelCall(
            ReviewStepStartTransactionCoordinator.StartedExecution started,
            String fingerprint, CallOutcome outcome, String reason) {
        return new ContractModelCallAudit(UUID.randomUUID(), null, started.attempt().attemptId(),
                started.attempt().attemptNo(), null, 1, CallPhase.TRANSPORT, fingerprint,
                "OPENAI_COMPATIBLE", "qwen-test", outcome, reason, 7, "STOP",
                10, 5, 15, null, NOW);
    }

    @Test
    void failureClosesIdempotencyButUnknownPreservesSameInProgressOperationIdentity() {
        var failed = fixture(ContractReviewStepType.TERM);
        var failedStart = failed.start().start(failed.command("failed-key"), ACTOR);
        failed.failure().fail(failedStart, "DEFINITIVE_INVALID_RESULT");
        assertThat(failed.store().findAttemptById(failedStart.attempt().attemptId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.FAILED);
        assertThat(failed.store().findAttemptById(failedStart.attempt().attemptId()).orElseThrow()
                .externalCallState()).isEqualTo(ReviewStepExternalCallState.NOT_STARTED);
        assertThat(failed.idempotency().findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, "failed-key").orElseThrow().status())
                .isEqualTo(IdempotencyStatus.FAILED);

        var unknown = fixture(ContractReviewStepType.SEMANTIC_AI);
        var unknownStart = unknown.start().start(unknown.command("unknown-key"), ACTOR);
        assertThatThrownBy(() -> unknown.unknown().unknown(unknownStart, "OUTCOME_UNCERTAIN"))
                .isInstanceOf(BusinessException.class);
        assertThat(unknown.store().findAttemptById(unknownStart.attempt().attemptId()).orElseThrow().status())
                .isEqualTo(ContractReviewStepStatus.RUNNING);
        unknownStart.context(unknown.externalRequests()).markExternalRequested("e".repeat(64));
        unknown.unknown().unknown(unknownStart, "OUTCOME_UNCERTAIN");
        var durableAttempt = unknown.store().findAttemptById(unknownStart.attempt().attemptId()).orElseThrow();
        assertThat(durableAttempt.status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
        assertThat(durableAttempt.operationIdentity()).isEqualTo(unknownStart.attempt().operationIdentity());
        assertThat(unknown.store().findById(unknownStart.run().runId()).orElseThrow().status())
                .isEqualTo(ContractReviewRunStatus.UNKNOWN);
        assertThat(unknown.idempotency().findByScopeAndKey(
                ReviewStepStartTransactionCoordinator.SCOPE, "unknown-key").orElseThrow().status())
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    private static ReviewStepStartTransactionCoordinator.StartedExecution start(
            ReviewStepStartTransactionCoordinator coordinator,
            ReviewStepStartTransactionCoordinator.StartCommand command, CountDownLatch gate) {
        try {
            gate.await();
            return coordinator.start(command, ACTOR);
        } catch (BusinessException conflict) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Fixture fixture(ContractReviewStepType type) {
        return fixture(type, "rule-v1", List.of());
    }

    private static Fixture fixture(ContractReviewStepType type, String ruleVersion,
            List<ContractRuleConfiguration> f02Configurations) {
        var store = new InMemoryContractReviewRunRepositories();
        var idempotency = new InMemoryIdempotencyAdapter();
        var audits = new ArrayList<OperationAuditEvent>();
        UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
        String canonical = "{\"contractVersion\":0}";
        var run = new ContractReviewRun(runId, projectId, contractId, 0, null, null, 1,
                canonical, ContractSnapshotCanonicalizer.sha256(canonical), "profile-v1", ruleVersion,
                "model-v1", "a".repeat(64), ACTOR.userId(), ContractReviewRunStatus.PENDING,
                NOW, null, null, null, null, null, 0);
        store.insert(run);
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, projectId,
                UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64),
                DocumentRole.CONTRACT_BODY, null, UUID.randomUUID(), null, ACTOR.userId(), NOW);
        store.insert(material);
        var manifest = new ReviewRunManifestFactory().create(runId, NOW,
                new ContractReviewExecutionPolicyResolver.ResolvedPolicy(
                        "profile-v1", ruleVersion, "model-v1", f02Configurations));
        manifest.steps().forEach(store::insert);
        manifest.attempts().forEach(store::insert);
        var step = manifest.steps().stream().filter(value -> value.stepType() == type)
                .findFirst().orElseThrow();
        var resolver = new ContractReviewExecutionPolicyResolver(() -> f02Configurations,
                new F02RulePolicyFingerprint());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var aggregator = new ReviewRunStatusAggregator();
        var results = new InMemoryContractReviewResultsRepositories(store, store, store);
        var start = new ReviewStepStartTransactionCoordinator(store, store, store, idempotency,
                audits::add, resolver, clock);
        var external = new ReviewStepExternalRequestTransactionCoordinator(store, clock);
        var complete = new ReviewStepCompleteTransactionCoordinator(store, store, store, store,
                results, results, idempotency, audits::add, aggregator, clock);
        var failure = new ReviewStepFailureTransactionCoordinator(store, store, store, idempotency,
                audits::add, aggregator, clock);
        var unknown = new ReviewStepUnknownTransactionCoordinator(store, store, store,
                audits::add, aggregator, clock);
        return new Fixture(store, results, idempotency, audits, material, step.stepId(), start,
                external, complete, failure, unknown, projectId, contractId, runId);
    }

    private static ContractReviewFinding finding(Fixture fixture,
            ReviewStepStartTransactionCoordinator.StartedExecution started, int ordinal) {
        return new ContractReviewFinding(UUID.randomUUID(), fixture.runId(), started.step().stepId(),
                started.attempt().attemptId(), ordinal, "BREACH", "CLAUSE", "NEEDS_REVIEW",
                null, null, "review clause", null, 1, started.step().stepPolicyVersion(),
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null, NOW, 0);
    }

    private static ContractReviewEvidence evidence(Fixture fixture,
            ReviewStepStartTransactionCoordinator.StartedExecution started,
            ContractReviewFinding finding, UUID parseResultId) {
        var material = fixture.material();
        return new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(), fixture.runId(),
                started.step().stepId(), started.attempt().attemptId(), 1, material.fileId(),
                material.fileSha256(), parseResultId, material.parseVersion(),
                material.canonicalTextSha256(),
                finding.findingType().endsWith("_COMPARISON") ? "COMPARISON" : "PROBLEM",
                "VALIDATED", "ground-v1",
                null, null, null, null, null, null, true, 0, 2, "d".repeat(64),
                "cl", 1, NOW, 0);
    }

    private static StepComputationResult result(
            ReviewStepStartTransactionCoordinator.StartedExecution started,
            ContractReviewFinding finding, ContractReviewEvidence evidence) {
        return new StepComputationResult("REVIEW_FINDING_SET", started.attempt().attemptId(),
                List.of(finding), List.of(evidence), null, null, null);
    }

    private static UUID stepId(Fixture fixture, ContractReviewStepType type) {
        return fixture.store().findStepsByRunId(fixture.runId()).stream()
                .filter(value -> value.stepType() == type).findFirst().orElseThrow().stepId();
    }

    private static ReviewStepCompleteTransactionCoordinator completeCoordinator(
            Fixture fixture, ContractReviewRunRepository runs) {
        return new ReviewStepCompleteTransactionCoordinator(runs, fixture.store(), fixture.store(),
                fixture.store(), fixture.results(), fixture.results(), fixture.idempotency(), ignored -> { },
                new ReviewRunStatusAggregator(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Models one DM8 row lock shared by independent application instances. */
    private static final class AdversarialRunRowRepository implements ContractReviewRunRepository {
        private final ContractReviewRunRepository delegate;
        private final ReentrantLock rowLock = new ReentrantLock();
        private final CyclicBarrier unlockedReaders = new CyclicBarrier(2);
        private final java.util.concurrent.atomic.AtomicInteger lockReads =
                new java.util.concurrent.atomic.AtomicInteger();

        private AdversarialRunRowRepository(ContractReviewRunRepository delegate) {
            this.delegate = delegate;
        }

        @Override public void insert(ContractReviewRun run) { delegate.insert(run); }
        @Override public java.util.Optional<ContractReviewRun> findById(UUID runId) {
            try {
                unlockedReaders.await();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            return delegate.findById(runId);
        }
        @Override public java.util.Optional<ContractReviewRun>
                findLatestByProjectIdAndContractIdAndRequestedBy(
                        UUID projectId, UUID contractId, String requestedBy) {
            return delegate.findLatestByProjectIdAndContractIdAndRequestedBy(
                    projectId, contractId, requestedBy);
        }
        @Override public java.util.Optional<ContractReviewRun> findByIdForUpdate(UUID runId) {
            rowLock.lock();
            lockReads.incrementAndGet();
            return delegate.findById(runId);
        }
        @Override public ContractReviewRun transition(ContractReviewRun expected, ContractReviewRun next) {
            try {
                return delegate.transition(expected, next);
            } finally {
                if (rowLock.isHeldByCurrentThread()) rowLock.unlock();
            }
        }
        int lockReads() { return lockReads.get(); }
    }

    private record Fixture(InMemoryContractReviewRunRepositories store,
            InMemoryContractReviewResultsRepositories results, InMemoryIdempotencyAdapter idempotency,
            List<OperationAuditEvent> audits, ContractReviewRunMaterial material, UUID stepId,
            ReviewStepStartTransactionCoordinator start,
            ReviewStepExternalRequestTransactionCoordinator externalRequests,
            ReviewStepCompleteTransactionCoordinator complete,
            ReviewStepFailureTransactionCoordinator failure,
            ReviewStepUnknownTransactionCoordinator unknown,
            UUID projectId, UUID contractId, UUID runId) {
        ReviewStepStartTransactionCoordinator.StartCommand command(String key) {
            return command(stepId, key);
        }
        ReviewStepStartTransactionCoordinator.StartCommand command(UUID selectedStepId, String key) {
            return new ReviewStepStartTransactionCoordinator.StartCommand(projectId, contractId,
                    runId, selectedStepId, key,
                    new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
        }
    }
}
