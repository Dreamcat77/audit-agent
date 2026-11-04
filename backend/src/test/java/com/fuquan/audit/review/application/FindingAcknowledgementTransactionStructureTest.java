package com.fuquan.audit.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.integration.dm8.Dm8FindingAcknowledgementRepository;
import com.fuquan.audit.integration.dm8.Dm8IdempotencyAdapter;
import com.fuquan.audit.integration.dm8.Dm8OperationAuditAdapter;
import com.fuquan.audit.review.application.port.FindingAcknowledgementRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.review.domain.RiskLevel;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class FindingAcknowledgementTransactionStructureTest {

    private static final Instant NOW = Instant.parse("2026-08-25T04:00:00Z");
    private static final UUID RESULT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void oneShortTransactionClaimsThenWritesFactAuditAndSuccessInExactOrder() {
        List<String> timeline = new ArrayList<>();
        Dependencies dependencies = new Dependencies(timeline, null);
        FindingAcknowledgementTransactionCoordinator coordinator = transactionalProxy(
                dependencies.coordinator(), new RecordingTransactionManager(timeline));

        FindingAcknowledgement returned = coordinator.create(
                acknowledgement(), result(), task(), command(), "fingerprint-1");

        assertThat(returned).isEqualTo(acknowledgement());
        assertThat(timeline).containsExactly(
                "BEGIN:create", "claim", "acknowledgement-create", "audit", "mark-succeeded", "COMMIT:create");
    }

    @ParameterizedTest
    @EnumSource(WriteStep.class)
    void everyWriteFailurePropagatesRollsBackAndNeverExecutesFalseSuccess(WriteStep failedStep) {
        List<String> timeline = new ArrayList<>();
        Dependencies dependencies = new Dependencies(timeline, failedStep);
        FindingAcknowledgementTransactionCoordinator coordinator = transactionalProxy(
                dependencies.coordinator(), new RecordingTransactionManager(timeline));

        assertThatThrownBy(() -> coordinator.create(
                acknowledgement(), result(), task(), command(), "fingerprint-1"))
                .isInstanceOf(RuntimeException.class);

        assertThat(timeline).contains("ROLLBACK:create");
        assertThat(timeline).doesNotContain("COMMIT:create");
        if (failedStep == WriteStep.CLAIM) {
            assertThat(timeline).containsExactly("BEGIN:create", "claim", "ROLLBACK:create");
        } else {
            assertThat(timeline).contains("rollback-uncommitted-claim");
        }
        if (failedStep.ordinal() <= WriteStep.ACKNOWLEDGEMENT.ordinal()) {
            assertThat(timeline).doesNotContain("audit", "mark-succeeded");
        } else if (failedStep == WriteStep.AUDIT) {
            assertThat(timeline).doesNotContain("mark-succeeded");
        }
    }

    @Test
    void coordinatorAndDm8AdaptersDeclareTheRequiredTransactionParticipation() throws Exception {
        Method coordinatorWrite = FindingAcknowledgementTransactionCoordinator.class.getMethod(
                "create", FindingAcknowledgement.class, AuditCheckResult.class, AuditTask.class,
                AcknowledgeFindingCommand.class, String.class);
        assertTransactional(coordinatorWrite, Propagation.REQUIRED);
        assertThat(FindingAcknowledgementTransactionCoordinator.class.getDeclaredMethods())
                .filteredOn(method -> AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class) != null)
                .singleElement()
                .isEqualTo(coordinatorWrite);

        assertTransactional(
                Dm8FindingAcknowledgementRepository.class.getMethod("create", FindingAcknowledgement.class),
                Propagation.MANDATORY);
        assertTransactional(
                Dm8OperationAuditAdapter.class.getMethod("append", OperationAuditEvent.class),
                Propagation.MANDATORY);
        assertTransactional(
                Dm8IdempotencyAdapter.class.getMethod("claim", IdempotencyRequest.class),
                Propagation.MANDATORY);
        assertTransactional(
                Dm8IdempotencyAdapter.class.getMethod(
                        "markSucceeded", IdempotencyRecord.class, String.class, String.class, Instant.class),
                Propagation.MANDATORY);
    }

    private void assertTransactional(Method method, Propagation propagation) {
        Transactional annotation = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(propagation);
    }

    private FindingAcknowledgementTransactionCoordinator transactionalProxy(
            FindingAcknowledgementTransactionCoordinator target,
            RecordingTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (FindingAcknowledgementTransactionCoordinator) proxyFactory.getProxy();
    }

    private FindingAcknowledgement acknowledgement() {
        return new FindingAcknowledgement(
                UUID.fromString("50000000-0000-0000-0000-000000000001"), RESULT_ID, "user-1",
                NOW, "request-1", "trace-1", 7);
    }

    private AuditCheckResult result() {
        return new AuditCheckResult(
                RESULT_ID, TASK_ID, "F02_TEST", "item-1", CheckStatus.FAIL, RiskLevel.RED,
                true, "actual", "expected", "detail", 7, NOW.minusSeconds(1));
    }

    private AuditTask task() {
        return new AuditTask(
                TASK_ID, "CONTRACT", UUID.fromString("30000000-0000-0000-0000-000000000001"),
                1, "F02_DETERMINISTIC", AuditTaskStatus.DONE, "creator", "task-request", "task-trace",
                NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10), 2);
    }

    private AcknowledgeFindingCommand command() {
        return new AcknowledgeFindingCommand(RESULT_ID, "key-1", "request-1", "trace-1");
    }

    private enum WriteStep {
        CLAIM,
        ACKNOWLEDGEMENT,
        AUDIT,
        MARK_SUCCEEDED
    }

    private static final class Dependencies {
        private final List<String> timeline;
        private final WriteStep failedStep;
        private final IdempotencyRecord claimed = new IdempotencyRecord(
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                FindingAcknowledgementService.IDEMPOTENCY_SCOPE, "key-1", "user-1",
                "AUDIT_CHECK_RESULT", RESULT_ID.toString(), "fingerprint-1", IdempotencyStatus.IN_PROGRESS,
                FindingAcknowledgementTransactionCoordinator.RESULT_RESOURCE_TYPE,
                "50000000-0000-0000-0000-000000000001", "request-1", "trace-1", NOW, null, 0);

        private Dependencies(List<String> timeline, WriteStep failedStep) {
            this.timeline = timeline;
            this.failedStep = failedStep;
        }

        FindingAcknowledgementTransactionCoordinator coordinator() {
            return new FindingAcknowledgementTransactionCoordinator(
                    acknowledgementRepository(), idempotencyPort(), operationAuditPort(),
                    mock(FlowDebugLogger.class));
        }

        private FindingAcknowledgementRepository acknowledgementRepository() {
            return new FindingAcknowledgementRepository() {
                @Override
                public void create(FindingAcknowledgement acknowledgement) {
                    timeline.add("acknowledgement-create");
                    failIf(WriteStep.ACKNOWLEDGEMENT);
                }

                @Override
                public Optional<FindingAcknowledgement> findByCheckResultId(UUID checkResultId) {
                    return Optional.empty();
                }
            };
        }

        private IdempotencyPort idempotencyPort() {
            return new IdempotencyPort() {
                @Override
                public IdempotencyClaim claim(IdempotencyRequest request) {
                    timeline.add("claim");
                    failIf(WriteStep.CLAIM);
                    return new IdempotencyClaim(true, claimed);
                }

                @Override
                public IdempotencyRecord markSucceeded(
                        IdempotencyRecord record, String type, String id, Instant completedAt) {
                    timeline.add("mark-succeeded");
                    failIf(WriteStep.MARK_SUCCEEDED);
                    return record;
                }

                @Override
                public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
                    throw new AssertionError("markFailed is not part of acknowledgement creation");
                }

                @Override
                public void rollbackUncommittedClaim(IdempotencyRecord record) {
                    timeline.add("rollback-uncommitted-claim");
                }
            };
        }

        private OperationAuditPort operationAuditPort() {
            return event -> {
                timeline.add("audit");
                failIf(WriteStep.AUDIT);
            };
        }

        private void failIf(WriteStep step) {
            if (failedStep == step) {
                throw new IllegalStateException("synthetic-" + step);
            }
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> timeline;

        private RecordingTransactionManager(List<String> timeline) {
            this.timeline = timeline;
        }

        @Override
        protected Object doGetTransaction() {
            return new RecordingTransaction();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            RecordingTransaction recording = (RecordingTransaction) transaction;
            recording.name = definition.getName();
            timeline.add("BEGIN:" + simpleName(recording.name));
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            RecordingTransaction recording = (RecordingTransaction) status.getTransaction();
            timeline.add("COMMIT:" + simpleName(recording.name));
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            RecordingTransaction recording = (RecordingTransaction) status.getTransaction();
            timeline.add("ROLLBACK:" + simpleName(recording.name));
        }

        private String simpleName(String transactionName) {
            return transactionName.substring(transactionName.lastIndexOf('.') + 1);
        }
    }

    private static final class RecordingTransaction {
        private String name;
    }
}
