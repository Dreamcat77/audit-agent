package com.fuquan.audit.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.review.infra.memory.InMemoryAuditTaskRepository;
import com.fuquan.audit.review.infra.memory.InMemoryFindingAcknowledgementRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FindingAcknowledgementConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-08-25T04:00:00Z");
    private static final UUID RESULT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void competingUsersProduceOneImmutableWinnerAndOneConflict() throws Exception {
        InMemoryAuditCheckResultRepository results = new InMemoryAuditCheckResultRepository();
        results.appendAll(List.of(new AuditCheckResult(
                RESULT_ID, TASK_ID, "F02_TEST", "item-1", CheckStatus.FAIL, RiskLevel.RED,
                true, "actual", "expected", "detail", 7, NOW.minusSeconds(10))));
        InMemoryAuditTaskRepository tasks = new InMemoryAuditTaskRepository();
        tasks.create(new AuditTask(
                TASK_ID, "CONTRACT", UUID.fromString("30000000-0000-0000-0000-000000000001"),
                1, "F02_DETERMINISTIC", AuditTaskStatus.DONE, "creator", "task-request", "task-trace",
                NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10), 2));
        InMemoryFindingAcknowledgementRepository acknowledgements =
                new InMemoryFindingAcknowledgementRepository(results);
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        List<OperationAuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        FlowDebugLogger flowDebug = org.mockito.Mockito.mock(FlowDebugLogger.class);
        FindingAcknowledgementTransactionCoordinator coordinator =
                new FindingAcknowledgementTransactionCoordinator(
                        acknowledgements, idempotency, audits::add, flowDebug);
        FindingAcknowledgementService service = new FindingAcknowledgementService(
                results, tasks, acknowledgements, acknowledgements,
                id -> Optional.of("Display " + id), coordinator,
                Clock.fixed(NOW, ZoneOffset.UTC), flowDebug);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = pool.submit(() -> invoke(
                    service, command("key-user-1"), user("user-1"), ready, start));
            Future<Object> second = pool.submit(() -> invoke(
                    service, command("key-user-2"), user("user-2"), ready, start));
            ready.await();
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(FindingAcknowledgementView.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance).hasSize(1);
            BusinessException conflict = (BusinessException) outcomes.stream()
                    .filter(BusinessException.class::isInstance).findFirst().orElseThrow();
            assertThat(conflict.errorCode()).isEqualTo(
                    com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT);

            FindingAcknowledgement winner = acknowledgements.findByCheckResultId(RESULT_ID).orElseThrow();
            FindingAcknowledgementView success = (FindingAcknowledgementView) outcomes.stream()
                    .filter(FindingAcknowledgementView.class::isInstance).findFirst().orElseThrow();
            assertThat(winner.id()).isEqualTo(success.id());
            assertThat(winner.acknowledgedBy()).isEqualTo(success.acknowledgedBy());
            assertThat(winner.acknowledgedBy()).isIn("user-1", "user-2");
            assertThat(audits).singleElement().extracting(OperationAuditEvent::userId)
                    .isEqualTo(winner.acknowledgedBy());
            assertThat(idempotency.size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Object invoke(
            FindingAcknowledgementService service,
            AcknowledgeFindingCommand command,
            CurrentUser user,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return service.acknowledge(command, user);
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private AcknowledgeFindingCommand command(String key) {
        return new AcknowledgeFindingCommand(RESULT_ID, key, "request-1", "trace-1");
    }

    private CurrentUser user(String actor) {
        return new CurrentUser(actor, "username-" + actor, "Display " + actor, Set.of("AUDITOR"));
    }
}
