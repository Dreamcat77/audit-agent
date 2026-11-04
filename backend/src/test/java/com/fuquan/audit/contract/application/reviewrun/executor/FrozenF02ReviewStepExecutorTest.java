package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.ContractReviewCommand;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.ContractReviewTransactionCoordinator;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.F02RulePolicyFingerprint;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.review.infra.memory.InMemoryAuditTaskRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FrozenF02ReviewStepExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:30:00Z");
    private static final CurrentUser ACTOR = new CurrentUser(
            "review-run-user", "auditor", "Audit User", Set.of("AUDITOR"));

    @Test
    void rejectsLegacyFrozenSourcePolicyBeforeCreatingAnyTask() {
        Fixture f = fixture();
        var r = f.run();
        var legacy = new ContractReviewRun(r.runId(), r.projectId(), r.contractId(), r.contractVersion(),
                r.sourceCandidateId(), r.sourceConfirmationId(), r.runSchemaVersion(), r.contractSnapshotCanonicalJson(),
                r.contractSnapshotSha256(), r.reviewProfileVersion(), "f02-policy-v1:" + "a".repeat(64),
                r.modelPolicyVersion(), r.inputSnapshotIdentity(), r.requestedBy(), r.status(), r.requestedAt(),
                r.startedAt(), r.completedAt(), r.failedAt(), r.unknownAt(), r.safeReasonCode(), r.versionNo());
        var s = f.step();
        var step = new ContractReviewStep(s.stepId(), s.runId(), s.stepType(), s.required(), s.status(),
                legacy.ruleVersion(), s.currentAttemptNo(), s.outputResourceType(), s.outputResourceId(),
                s.safeReasonCode(), s.startedAt(), s.completedAt(), s.versionNo());
        var context = new ReviewExecutionContext(legacy, step, f.attempt(), ACTOR, f.context().metadata(),
                List.of(f.frozenConfiguration()), value -> { });
        assertThatThrownBy(() -> f.executor().execute(new FrozenReviewInput(legacy, f.frozenContract(), List.of()), context))
                .isInstanceOf(BusinessException.class);
        assertThat(f.tasks().findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).isEmpty();
    }

    @Test
    void refusesDifferentExecutionConfigurationsBeforeCreatingAnyTask() {
        Fixture f = fixture();
        var context = new ReviewExecutionContext(f.run(), f.step(), f.attempt(), ACTOR, f.context().metadata(),
                List.of(), value -> { });
        assertThatThrownBy(() -> f.executor().execute(f.input(), context)).isInstanceOf(BusinessException.class);
        assertThat(f.tasks().findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).isEmpty();
    }

    @Test
    void executesFrozenContractAndExactConfigurationsIntoRealF02TruthOnly() {
        Fixture fixture = fixture();

        var computed = fixture.executor().execute(fixture.input(), fixture.context());

        assertThat(computed.outputResourceType()).isEqualTo("AUDIT_TASK");
        assertThat(computed.findings()).isEmpty();
        assertThat(computed.evidence()).isEmpty();
        assertThat(computed.providerRequestId()).isNull();
        assertThat(computed.provider()).isNull();
        assertThat(computed.model()).isNull();
        var task = fixture.tasks().findById(computed.outputResourceId()).orElseThrow();
        assertThat(task.status()).isEqualTo(AuditTaskStatus.DONE);
        assertThat(task.sourceId()).isEqualTo(fixture.frozenContract().id());
        assertThat(task.createdBy()).isEqualTo(ACTOR.userId());
        assertThat(task.requestId()).isEqualTo("request-1");
        assertThat(task.traceId()).isEqualTo("trace-1");
        assertThat(fixture.results().findByTaskId(task.id()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.ruleCode()).isEqualTo(F02RuleCodes.AWARD_AMOUNT_MATCH);
                    assertThat(result.checkStatus()).isEqualTo(CheckStatus.FAIL);
                    assertThat(result.riskLevel()).isEqualTo(RiskLevel.YELLOW);
                    assertThat(result.blockSign()).isFalse();
                    assertThat(result.actualValue()).isEqualTo("876000.00");
                    assertThat(result.expectedValue()).isEqualTo("849000.00");
                    assertThat(result.ruleVersion()).isEqualTo(9);
                });
        assertThat(fixture.currentContractReads()).hasValue(0);
        assertThat(fixture.currentConfigurationReads()).hasValue(0);
    }

    @Test
    void stableServerInternalKeyReopensTaskAfterReviewStepCompletionWasInterrupted() {
        Fixture fixture = fixture();
        String expectedKey = "reviewrun-f02:" + fixture.run().runId() + ":"
                + fixture.step().stepId() + ":" + fixture.attempt().attemptId();
        assertThat(FrozenF02ReviewStepExecutor.internalKey(
                fixture.run().runId(), fixture.step().stepId(), fixture.attempt().attemptId()))
                .isEqualTo(expectedKey);

        var first = fixture.executor().execute(fixture.input(), fixture.context());
        var replay = fixture.executor().execute(fixture.input(), fixture.context());

        assertThat(replay.outputResourceId()).isEqualTo(first.outputResourceId());
        assertThat(fixture.tasks().findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE))
                .extracting(task -> task.id())
                .containsExactly(first.outputResourceId());
        assertThat(fixture.results().findByTaskId(first.outputResourceId())).hasSize(1);
        assertThat(fixture.idempotency().findByScopeAndKey(
                ContractReviewService.IDEMPOTENCY_SCOPE, expectedKey))
                .get()
                .extracting(record -> record.status())
                .isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(expectedKey).hasSizeLessThanOrEqualTo(128)
                .matches("^[A-Za-z0-9._:-]+$");
    }

    @Test
    void frozenSeamRejectsCommandForDifferentContractBeforeCreatingAuditTask() {
        Fixture fixture = fixture();
        var mismatched = new ContractReviewCommand(
                UUID.randomUUID(), "reviewrun-f02:mismatched-contract",
                "request-1", "trace-1", "127.0.0.1");

        assertThatThrownBy(() -> fixture.reviews().executeFrozen(
                fixture.frozenContract(), List.of(fixture.frozenConfiguration()), ACTOR, mismatched))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(fixture.tasks().findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE))
                .isEmpty();
    }

    @Test
    void directClientReviewCannotClaimTheReservedServerInternalKeyNamespace() {
        Fixture fixture = fixture();
        String reserved = FrozenF02ReviewStepExecutor.internalKey(
                fixture.run().runId(), fixture.step().stepId(), fixture.attempt().attemptId());

        assertThatThrownBy(() -> fixture.reviews().review(new ContractReviewCommand(
                fixture.frozenContract().id(), reserved,
                "client-request", "client-trace", "127.0.0.1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.INVALID_ARGUMENT));
        assertThat(fixture.tasks().findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE))
                .isEmpty();
    }

    private Fixture fixture() {
        Contract frozen = LocalContractFixtures.contracts().stream()
                .filter(contract -> contract.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID))
                .findFirst().orElseThrow();
        Contract current = currentPassingContract(frozen);
        AtomicInteger contractReads = new AtomicInteger();
        AtomicInteger configurationReads = new AtomicInteger();
        ContractRuleConfiguration frozenConfiguration = new ContractRuleConfiguration(
                F02RuleCodes.AWARD_AMOUNT_MATCH, "Frozen award amount", true,
                RiskLevel.YELLOW, false, 9, "frozen", NOW);
        ContractRuleConfiguration currentConfiguration = new ContractRuleConfiguration(
                F02RuleCodes.AWARD_AMOUNT_MATCH, "Current award amount", true,
                RiskLevel.RED, true, 10, "current", NOW.plusSeconds(1));
        InMemoryAuditTaskRepository tasks = new InMemoryAuditTaskRepository();
        InMemoryAuditCheckResultRepository results = new InMemoryAuditCheckResultRepository();
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        FlowDebugLogger flow = new FlowDebugLogger(new ObservabilityProperties());
        ContractReviewService reviews = new ContractReviewService(
                contractId -> {
                    contractReads.incrementAndGet();
                    return contractId.equals(current.id()) ? Optional.of(current) : Optional.empty();
                },
                () -> {
                    configurationReads.incrementAndGet();
                    return List.of(currentConfiguration);
                },
                new ContractReviewTransactionCoordinator(
                        results, tasks, idempotency, event -> { }, flow),
                Optional::<CurrentUser>empty,
                Clock.fixed(NOW, ZoneOffset.UTC),
                flow,
                List.of(new AwardAmountMatchRule()));
        String ruleVersion = new F02RulePolicyFingerprint().fingerprint(List.of(frozenConfiguration));
        UUID runId = UUID.randomUUID();
        String canonical = "{}";
        ContractReviewRun run = new ContractReviewRun(
                runId, UUID.randomUUID(), frozen.id(), frozen.versionNo(), null, null, 1,
                canonical, ContractSnapshotCanonicalizer.sha256(canonical), "profile-v1", ruleVersion,
                "model-v1", "a".repeat(64), ACTOR.userId(), ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1);
        ContractReviewStep step = new ContractReviewStep(
                UUID.randomUUID(), runId, ContractReviewStepType.F02_DETERMINISTIC, true,
                ContractReviewStepStatus.RUNNING, ruleVersion, 1, null, null, null,
                NOW, null, 1);
        ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(
                UUID.randomUUID(), runId, step.stepId(), 1, "b".repeat(64),
                ContractReviewStepStatus.RUNNING, null, null, NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
        ReviewExecutionContext context = new ReviewExecutionContext(
                run, step, attempt, ACTOR,
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"),
                List.of(frozenConfiguration), fingerprint -> { });
        return new Fixture(new FrozenF02ReviewStepExecutor(reviews), reviews,
                new FrozenReviewInput(run, frozen, List.of()), context,
                run, step, attempt, frozen, frozenConfiguration, tasks, results, idempotency,
                contractReads, configurationReads);
    }

    private Contract currentPassingContract(Contract frozen) {
        AwardNoticeReference old = frozen.awardNotice();
        var matchingAward = new AwardNoticeReference(
                old.id(), old.contractId(), old.noticeNo(), old.projectName(),
                frozen.partyB(), frozen.partyBCreditCode(), frozen.contractAmount(),
                old.winningDate(), old.sourceRef());
        return new Contract(
                frozen.id(), frozen.contractNo(), frozen.contractName(), frozen.contractType(),
                frozen.partyA(), frozen.partyB(), frozen.partyBCreditCode(), frozen.contractAmount(),
                frozen.amountInWords(), frozen.status(), frozen.versionNo() + 1,
                frozen.createdAt(), NOW, frozen.lineItems(), matchingAward);
    }

    private record Fixture(
            FrozenF02ReviewStepExecutor executor,
            ContractReviewService reviews,
            FrozenReviewInput input,
            ReviewExecutionContext context,
            ContractReviewRun run,
            ContractReviewStep step,
            ContractReviewStepAttempt attempt,
            Contract frozenContract,
            ContractRuleConfiguration frozenConfiguration,
            InMemoryAuditTaskRepository tasks,
            InMemoryAuditCheckResultRepository results,
            InMemoryIdempotencyAdapter idempotency,
            AtomicInteger currentContractReads,
            AtomicInteger currentConfigurationReads) {
    }
}
