package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRepository;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.review.infra.memory.InMemoryAuditTaskRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ContractReviewTransactionStructureTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");
    private final FlowDebugLogger flowLogger = new FlowDebugLogger(new ObservabilityProperties());

    @Test
    void startCommitsBeforeRulesAndCompleteCommitsAllSuccessFactsInOrder() {
        List<String> transactionEvents = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(transactionEvents);
        InMemoryAuditTaskRepository taskDelegate = new InMemoryAuditTaskRepository();
        TrackingTaskRepository tasks = new TrackingTaskRepository(taskDelegate, writes);
        TrackingIdempotencyPort idempotency = new TrackingIdempotencyPort(writes);
        TrackingResultRepository results = new TrackingResultRepository(writes, false);
        List<OperationAuditEvent> audits = new ArrayList<>();
        OperationAuditPort audit = event -> {
            writes.add("audit-" + event.eventType());
            audits.add(event);
        };
        ContractReviewTransactionCoordinator coordinator = transactionalProxy(
                new ContractReviewTransactionCoordinator(results, tasks, idempotency, audit, flowLogger),
                transactionManager);
        ContractReviewRule observingRule = rule((context, configuration) -> {
            assertThat(hasTransactionEvent(transactionEvents, "COMMIT", "start")).isTrue();
            assertThat(hasTransactionEvent(transactionEvents, "BEGIN", "complete")).isFalse();
            assertThat(taskDelegate.findLatest(
                            "CONTRACT", LocalContractFixtures.PASS_CONTRACT_ID,
                            ContractReviewService.REVIEW_TYPE))
                    .get().extracting(AuditTask::status).isEqualTo(AuditTaskStatus.PROCESSING);
            assertThat(idempotency.current.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
            return List.of(ContractReviewFinding.pass(
                    configuration, "amount", "100.00", "100.00", "matched"));
        });
        ContractReviewService service = service(coordinator, observingRule);

        ContractReviewResult result = service.review(command("tx-success"));

        assertThat(result.task().status()).isEqualTo(AuditTaskStatus.DONE);
        assertThat(transactionEvents).anySatisfy(event -> assertThat(event).contains("COMMIT").contains("start"));
        assertThat(transactionEvents).anySatisfy(event -> assertThat(event).contains("COMMIT").contains("complete"));
        assertThat(writes).containsExactly(
                "idempotency-claim",
                "task-create",
                "task-PENDING-PROCESSING",
                "audit-CONTRACT_REVIEW_STARTED",
                "results-append",
                "task-PROCESSING-DONE",
                "audit-CONTRACT_REVIEW_COMPLETED",
                "idempotency-succeeded");
        assertThat(audits.get(0).resultStatus().name()).isEqualTo("SUCCESS");
    }

    @Test
    void completePersistenceFailureRollsBackBeforeIndependentFailureClosureCommits() throws Exception {
        List<String> transactionEvents = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(transactionEvents);
        InMemoryAuditTaskRepository taskDelegate = new InMemoryAuditTaskRepository();
        TrackingTaskRepository tasks = new TrackingTaskRepository(taskDelegate, writes);
        TrackingIdempotencyPort idempotency = new TrackingIdempotencyPort(writes);
        TrackingResultRepository results = new TrackingResultRepository(writes, true);
        List<OperationAuditEvent> audits = new ArrayList<>();
        OperationAuditPort audit = event -> {
            writes.add("audit-" + event.eventType());
            audits.add(event);
        };
        ContractReviewTransactionCoordinator coordinator = transactionalProxy(
                new ContractReviewTransactionCoordinator(results, tasks, idempotency, audit, flowLogger),
                transactionManager);
        ContractReviewService service = service(coordinator, rule((context, configuration) -> List.of(
                ContractReviewFinding.pass(
                        configuration, "amount", "100.00", "100.00", "matched"))));

        assertThatThrownBy(() -> service.review(command("tx-failure")))
                .isInstanceOf(ContractReviewExecutionFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        int completeRollback = transactionEventIndex(transactionEvents, "ROLLBACK", "complete");
        int failureBegin = transactionEventIndex(transactionEvents, "BEGIN", "fail");
        int failureCommit = transactionEventIndex(transactionEvents, "COMMIT", "fail");
        assertThat(completeRollback).isGreaterThanOrEqualTo(0);
        assertThat(failureBegin).isGreaterThan(completeRollback);
        assertThat(failureCommit).isGreaterThan(failureBegin);
        assertThat(taskDelegate.findLatest(
                        "CONTRACT", LocalContractFixtures.PASS_CONTRACT_ID,
                        ContractReviewService.REVIEW_TYPE))
                .get().extracting(AuditTask::status).isEqualTo(AuditTaskStatus.FAILED);
        assertThat(idempotency.current.status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(idempotency.current.resultResourceId()).isNotBlank();
        assertThat(audits).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_REVIEW_STARTED", "CONTRACT_REVIEW_FAILED");

        Transactional failureTransaction = AnnotatedElementUtils.findMergedAnnotation(
                ContractReviewTransactionCoordinator.class.getMethod(
                        "fail", ContractReviewStart.class, CurrentUser.class,
                        com.fuquan.audit.contract.domain.Contract.class, ContractReviewCommand.class,
                        RuntimeException.class, Instant.class),
                Transactional.class);
        assertThat(failureTransaction).isNotNull();
        assertThat(failureTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private ContractReviewService service(
            ContractReviewTransactionCoordinator coordinator, ContractReviewRule rule) {
        ContractRuleConfiguration configuration = configuration();
        ContractRuleConfigRepository configurations = () -> List.of(configuration);
        AuthContext auth = () -> Optional.of(user());
        return new ContractReviewService(
                new InMemoryContractRepository(flowLogger), configurations, coordinator, auth,
                Clock.fixed(NOW, ZoneOffset.UTC), flowLogger, List.of(rule));
    }

    private ContractReviewRule rule(RuleEvaluation evaluation) {
        return new ContractReviewRule() {
            @Override
            public String ruleCode() {
                return "F02_TX_STRUCTURE";
            }

            @Override
            public List<ContractReviewFinding> evaluate(
                    com.fuquan.audit.contract.domain.ContractReviewContext context,
                    ContractRuleConfiguration configuration) {
                return evaluation.evaluate(context, configuration);
            }
        };
    }

    private ContractRuleConfiguration configuration() {
        return new ContractRuleConfiguration(
                "F02_TX_STRUCTURE", "Transaction structure", true, RiskLevel.RED,
                true, 1, "test-only deterministic rule", NOW);
    }

    private ContractReviewCommand command(String key) {
        return new ContractReviewCommand(
                LocalContractFixtures.PASS_CONTRACT_ID, key, "request-1", "trace-1", "127.0.0.1");
    }

    private CurrentUser user() {
        return new CurrentUser("user-1", "auditor", "Audit User", Set.of("AUDITOR"));
    }

    private ContractReviewTransactionCoordinator transactionalProxy(
            ContractReviewTransactionCoordinator target,
            RecordingTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (ContractReviewTransactionCoordinator) proxyFactory.getProxy();
    }

    private boolean hasTransactionEvent(List<String> events, String phase, String method) {
        return transactionEventIndex(events, phase, method) >= 0;
    }

    private int transactionEventIndex(List<String> events, String phase, String method) {
        for (int index = 0; index < events.size(); index++) {
            String event = events.get(index);
            if (event.startsWith(phase + ":") && event.endsWith("." + method)) {
                return index;
            }
        }
        return -1;
    }

    @FunctionalInterface
    private interface RuleEvaluation {
        List<ContractReviewFinding> evaluate(
                com.fuquan.audit.contract.domain.ContractReviewContext context,
                ContractRuleConfiguration configuration);
    }

    private static final class TrackingResultRepository implements AuditCheckResultRepository {
        private final InMemoryAuditCheckResultRepository delegate = new InMemoryAuditCheckResultRepository();
        private final List<String> writes;
        private final boolean failAppend;

        private TrackingResultRepository(List<String> writes, boolean failAppend) {
            this.writes = writes;
            this.failAppend = failAppend;
        }

        @Override
        public void appendAll(List<AuditCheckResult> results) {
            writes.add("results-append");
            if (failAppend) {
                throw new IllegalStateException("synthetic persistence failure");
            }
            delegate.appendAll(results);
        }

        @Override
        public List<AuditCheckResult> findByTaskId(UUID taskId) {
            return delegate.findByTaskId(taskId);
        }

        @Override
        public Optional<AuditCheckResult> findById(UUID id) {
            return delegate.findById(id);
        }
    }

    private static final class TrackingTaskRepository implements AuditTaskRepository {
        private final InMemoryAuditTaskRepository delegate;
        private final List<String> writes;

        private TrackingTaskRepository(InMemoryAuditTaskRepository delegate, List<String> writes) {
            this.delegate = delegate;
            this.writes = writes;
        }

        @Override
        public AuditTask create(AuditTask task) {
            writes.add("task-create");
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
            writes.add("task-" + expectedStatus + "-" + targetStatus);
            return delegate.transition(id, expectedStatus, expectedVersion, targetStatus, occurredAt);
        }
    }

    private static final class TrackingIdempotencyPort implements IdempotencyPort {
        private final InMemoryIdempotencyAdapter delegate = new InMemoryIdempotencyAdapter();
        private final List<String> writes;
        private IdempotencyRecord current;

        private TrackingIdempotencyPort(List<String> writes) {
            this.writes = writes;
        }

        @Override
        public IdempotencyClaim claim(IdempotencyRequest request) {
            writes.add("idempotency-claim");
            IdempotencyClaim claim = delegate.claim(request);
            current = claim.record();
            return claim;
        }

        @Override
        public IdempotencyRecord markSucceeded(
                IdempotencyRecord record,
                String resultResourceType,
                String resultResourceId,
                Instant completedAt) {
            writes.add("idempotency-succeeded");
            current = delegate.markSucceeded(record, resultResourceType, resultResourceId, completedAt);
            return current;
        }

        @Override
        public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
            writes.add("idempotency-failed");
            current = delegate.markFailed(record, completedAt);
            return current;
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> events;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new RecordingTransaction();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            RecordingTransaction recording = (RecordingTransaction) transaction;
            recording.name = definition.getName();
            events.add("BEGIN:" + recording.name);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            RecordingTransaction recording = (RecordingTransaction) status.getTransaction();
            events.add("COMMIT:" + recording.name);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            RecordingTransaction recording = (RecordingTransaction) status.getTransaction();
            events.add("ROLLBACK:" + recording.name);
        }
    }

    private static final class RecordingTransaction {
        private String name;
    }
}
