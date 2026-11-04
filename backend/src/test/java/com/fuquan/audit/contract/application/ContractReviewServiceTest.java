package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ChineseRmbUppercaseParser;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.rule.AmountWordsMatchRule;
import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.contract.domain.rule.AwardSupplierMatchRule;
import com.fuquan.audit.contract.domain.rule.LineItemArithmeticRule;
import com.fuquan.audit.contract.domain.rule.LineSumMatchRule;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRepository;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRuleConfigRepository;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.infra.memory.InMemoryAuditTaskRepository;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ContractReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");
    private final FlowDebugLogger flowLogger = new FlowDebugLogger(new ObservabilityProperties());

    @Test
    void inProgressDuplicateDoesNotCreateAuditTask() {
        Fixture fixture = normalFixture();
        String key = "in-progress-key";
        var contract = fixture.contractRepository.findById(LocalContractFixtures.PASS_CONTRACT_ID).orElseThrow();
        var configurations = fixture.configRepository.findEnabledForF02();
        String fingerprint = IdempotencyFingerprint.forF02(
                ContractReviewService.IDEMPOTENCY_SCOPE, user().userId(), contract, configurations);
        fixture.idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, key, user().userId(), "CONTRACT",
                contract.id().toString(), fingerprint, "AUDIT_TASK", UUID.randomUUID().toString(),
                "first-request", "trace-1", NOW));

        assertThatThrownBy(() -> fixture.service.review(command(contract.id(), key)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT))
                .hasMessageContaining("in progress");
        assertThat(fixture.tasks.findLatest("CONTRACT", contract.id(), ContractReviewService.REVIEW_TYPE))
                .isEmpty();
    }

    @Test
    void failedDuplicateRequiresNewIdempotencyKey() {
        Fixture fixture = normalFixture();
        String key = "failed-key";
        var contract = fixture.contractRepository.findById(LocalContractFixtures.PASS_CONTRACT_ID).orElseThrow();
        var configs = fixture.configRepository.findEnabledForF02();
        String fingerprint = IdempotencyFingerprint.forF02(
                ContractReviewService.IDEMPOTENCY_SCOPE, user().userId(), contract, configs);
        var claim = fixture.idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, key, user().userId(), "CONTRACT",
                contract.id().toString(), fingerprint, "AUDIT_TASK", UUID.randomUUID().toString(),
                "first-request", "trace-1", NOW));
        fixture.idempotency.markFailed(claim.record(), NOW.plusSeconds(1));

        assertThatThrownBy(() -> fixture.service.review(command(contract.id(), key)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("use a new Idempotency-Key");
    }

    @Test
    void differentKeysCreateDistinctReviewAttempts() {
        Fixture fixture = normalFixture();

        var first = fixture.service.review(command(LocalContractFixtures.PASS_CONTRACT_ID, "attempt-1"));
        var second = fixture.service.review(command(LocalContractFixtures.PASS_CONTRACT_ID, "attempt-2"));

        assertThat(second.task().id()).isNotEqualTo(first.task().id());
        assertThat(first.task().status()).isEqualTo(AuditTaskStatus.DONE);
        assertThat(second.task().status()).isEqualTo(AuditTaskStatus.DONE);
    }

    @Test
    void ruleExecutionFailureTransitionsTaskAndIdempotencyToFailedAndAuditsSafely() {
        List<OperationAuditEvent> audits = new ArrayList<>();
        InMemoryAuditTaskRepository tasks = new InMemoryAuditTaskRepository();
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        ContractRuleConfigRepository oneConfig = () -> List.of(
                new InMemoryContractRuleConfigRepository().findEnabledForF02().stream()
                        .filter(config -> config.ruleCode().equals(F02RuleCodes.AMOUNT_WORDS_MATCH))
                        .findFirst().orElseThrow());
        ContractReviewRule throwingRule = new ContractReviewRule() {
            @Override
            public String ruleCode() {
                return F02RuleCodes.AMOUNT_WORDS_MATCH;
            }

            @Override
            public List<ContractReviewFinding> evaluate(
                    com.fuquan.audit.contract.domain.ContractReviewContext context,
                    com.fuquan.audit.contract.domain.ContractRuleConfiguration configuration) {
                throw new IllegalStateException("synthetic safe failure");
            }
        };
        ContractReviewService service = service(
                tasks, idempotency, audits::add, oneConfig, List.of(throwingRule));

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "rule-failure")))
                .isInstanceOf(ContractReviewExecutionFailedException.class)
                .hasMessage("internal server error");

        var task = tasks.findLatest(
                "CONTRACT", LocalContractFixtures.PASS_CONTRACT_ID, ContractReviewService.REVIEW_TYPE)
                .orElseThrow();
        assertThat(task.status()).isEqualTo(AuditTaskStatus.FAILED);
        assertThat(audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_REVIEW_STARTED", "CONTRACT_REVIEW_FAILED");
        assertThat(audits.get(0).resultStatus()).isEqualTo(OperationAuditResultStatus.SUCCESS);
        assertThat(audits.get(1).detail()).isEqualTo("IllegalStateException")
                .doesNotContain("synthetic safe failure");
        assertThat(idempotency.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, "rule-failure", user().userId(), "CONTRACT",
                LocalContractFixtures.PASS_CONTRACT_ID.toString(), "unused", "AUDIT_TASK", task.id().toString(),
                "later", "trace-later", NOW)).record().status())
                .isEqualTo(com.fuquan.audit.common.application.port.IdempotencyStatus.FAILED);
    }

    @Test
    void durableAuditFailureIsNeverSwallowed() {
        OperationAuditPort failingAudit = event -> {
            throw new IllegalStateException("durable audit unavailable");
        };
        ContractReviewService service = service(
                new InMemoryAuditTaskRepository(), new InMemoryIdempotencyAdapter(), failingAudit,
                new InMemoryContractRuleConfigRepository(), normalRules());

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "audit-failure")))
                .isInstanceOf(ContractReviewExecutionFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidIdempotencyKeyMapsTo400BusinessError() {
        Fixture fixture = normalFixture();

        assertThatThrownBy(() -> fixture.service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "contains whitespace")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void auditTaskTransitionConflictMapsTo409BusinessError() {
        AuditTaskRepository conflictingTasks = new AuditTaskRepository() {
            @Override
            public AuditTask create(AuditTask task) {
                return task;
            }

            @Override
            public Optional<AuditTask> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
                return Optional.empty();
            }

            @Override
            public AuditTask transition(
                    UUID id,
                    AuditTaskStatus expectedStatus,
                    long expectedVersion,
                    AuditTaskStatus targetStatus,
                    Instant occurredAt) {
                throw new AuditTaskConcurrencyException("synthetic stale version");
            }
        };
        ContractReviewService service = new ContractReviewService(
                new InMemoryContractRepository(flowLogger), new InMemoryContractRuleConfigRepository(),
                coordinator(new InMemoryAuditCheckResultRepository(), conflictingTasks,
                        new InMemoryIdempotencyAdapter(), event -> { }), authContext(),
                Clock.fixed(NOW, ZoneOffset.UTC), flowLogger, normalRules());

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "task-conflict")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void duplicateAuditTaskCreateMapsTo409BusinessError() {
        AuditTaskRepository duplicateTasks = new AuditTaskRepository() {
            @Override
            public AuditTask create(AuditTask task) {
                throw new AuditTaskConcurrencyException("synthetic duplicate task");
            }

            @Override
            public Optional<AuditTask> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
                return Optional.empty();
            }

            @Override
            public AuditTask transition(
                    UUID id,
                    AuditTaskStatus expectedStatus,
                    long expectedVersion,
                    AuditTaskStatus targetStatus,
                    Instant occurredAt) {
                throw new AssertionError("transition must not follow a duplicate create");
            }
        };
        ContractReviewService service = new ContractReviewService(
                new InMemoryContractRepository(flowLogger), new InMemoryContractRuleConfigRepository(),
                coordinator(new InMemoryAuditCheckResultRepository(), duplicateTasks,
                        new InMemoryIdempotencyAdapter(), event -> { }),
                authContext(), Clock.fixed(NOW, ZoneOffset.UTC), flowLogger, normalRules());

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "duplicate-task")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void completeConcurrencyConflictIs409AndDoesNotForceTaskToFailed() {
        InMemoryAuditTaskRepository delegate = new InMemoryAuditTaskRepository();
        AtomicBoolean failureTransitionAttempted = new AtomicBoolean();
        AuditTaskRepository tasks = new AuditTaskRepository() {
            @Override
            public AuditTask create(AuditTask task) {
                return delegate.create(task);
            }

            @Override
            public Optional<AuditTask> findById(UUID id) {
                return delegate.findById(id);
            }

            @Override
            public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
                return delegate.findLatest(sourceType, sourceId, reviewType);
            }

            @Override
            public AuditTask transition(
                    UUID id,
                    AuditTaskStatus expectedStatus,
                    long expectedVersion,
                    AuditTaskStatus targetStatus,
                    Instant occurredAt) {
                if (targetStatus == AuditTaskStatus.DONE) {
                    throw new AuditTaskConcurrencyException("synthetic complete conflict");
                }
                if (targetStatus == AuditTaskStatus.FAILED) {
                    failureTransitionAttempted.set(true);
                }
                return delegate.transition(id, expectedStatus, expectedVersion, targetStatus, occurredAt);
            }
        };
        ContractReviewService service = new ContractReviewService(
                new InMemoryContractRepository(flowLogger), new InMemoryContractRuleConfigRepository(),
                coordinator(new InMemoryAuditCheckResultRepository(), tasks,
                        new InMemoryIdempotencyAdapter(), event -> { }),
                authContext(), Clock.fixed(NOW, ZoneOffset.UTC), flowLogger, normalRules());

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "complete-conflict")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(failureTransitionAttempted).isFalse();
        assertThat(delegate.findLatest(
                        "CONTRACT", LocalContractFixtures.PASS_CONTRACT_ID,
                        ContractReviewService.REVIEW_TYPE))
                .get().extracting(AuditTask::status).isEqualTo(AuditTaskStatus.PROCESSING);
    }

    @Test
    void closureFailurePreservesOriginalCauseAndAddsSafeClosureFailureAsSuppressed() {
        ContractRuleConfigRepository oneConfig = () -> List.of(
                new InMemoryContractRuleConfigRepository().findEnabledForF02().get(0));
        ContractReviewRule throwingRule = new ContractReviewRule() {
            @Override
            public String ruleCode() {
                return oneConfig.findEnabledForF02().get(0).ruleCode();
            }

            @Override
            public List<ContractReviewFinding> evaluate(
                    com.fuquan.audit.contract.domain.ContractReviewContext context,
                    com.fuquan.audit.contract.domain.ContractRuleConfiguration configuration) {
                throw new IllegalArgumentException("original rule failure");
            }
        };
        OperationAuditPort closureFailingAudit = event -> {
            if (event.eventType().equals("CONTRACT_REVIEW_FAILED")) {
                throw new IllegalStateException("closure persistence failure");
            }
        };
        ContractReviewService service = service(
                new InMemoryAuditTaskRepository(), new InMemoryIdempotencyAdapter(),
                closureFailingAudit, oneConfig, List.of(throwingRule));

        assertThatThrownBy(() -> service.review(command(
                LocalContractFixtures.PASS_CONTRACT_ID, "closure-failure")))
                .isInstanceOf(ContractReviewExecutionFailedException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getCause().getSuppressed())
                            .singleElement()
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    private Fixture normalFixture() {
        InMemoryAuditTaskRepository tasks = new InMemoryAuditTaskRepository();
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        InMemoryContractRepository contracts = new InMemoryContractRepository(flowLogger);
        InMemoryContractRuleConfigRepository configurations = new InMemoryContractRuleConfigRepository();
        ContractReviewService service = new ContractReviewService(
                contracts, configurations,
                coordinator(new InMemoryAuditCheckResultRepository(), tasks, idempotency, event -> { }),
                authContext(), Clock.fixed(NOW, ZoneOffset.UTC),
                flowLogger, normalRules());
        return new Fixture(service, tasks, idempotency, contracts, configurations);
    }

    private ContractReviewService service(
            InMemoryAuditTaskRepository tasks,
            IdempotencyPort idempotency,
            OperationAuditPort audit,
            ContractRuleConfigRepository configurations,
            List<ContractReviewRule> rules) {
        return new ContractReviewService(
                new InMemoryContractRepository(flowLogger), configurations,
                coordinator(new InMemoryAuditCheckResultRepository(), tasks, idempotency, audit),
                authContext(), Clock.fixed(NOW, ZoneOffset.UTC), flowLogger, rules);
    }

    private ContractReviewTransactionCoordinator coordinator(
            InMemoryAuditCheckResultRepository results,
            AuditTaskRepository tasks,
            IdempotencyPort idempotency,
            OperationAuditPort audit) {
        return new ContractReviewTransactionCoordinator(results, tasks, idempotency, audit, flowLogger);
    }

    private List<ContractReviewRule> normalRules() {
        return List.of(
                new AmountWordsMatchRule(new ChineseRmbUppercaseParser()),
                new LineItemArithmeticRule(),
                new LineSumMatchRule(),
                new AwardAmountMatchRule(),
                new AwardSupplierMatchRule());
    }

    private ContractReviewCommand command(UUID contractId, String key) {
        return new ContractReviewCommand(contractId, key, "request-1", "trace-1", "127.0.0.1");
    }

    private AuthContext authContext() {
        return () -> Optional.of(user());
    }

    private CurrentUser user() {
        return new CurrentUser("user-1", "auditor", "Audit User", Set.of("AUDITOR"));
    }

    private record Fixture(
            ContractReviewService service,
            InMemoryAuditTaskRepository tasks,
            InMemoryIdempotencyAdapter idempotency,
            InMemoryContractRepository contractRepository,
            InMemoryContractRuleConfigRepository configRepository) {
    }
}
