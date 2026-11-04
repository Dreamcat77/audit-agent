package com.fuquan.audit.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class FindingAcknowledgementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T04:00:00Z");
    private static final UUID RESULT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RESULT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String EXPECTED_FINGERPRINT =
            "9e17db83b41e7b8c343a26c8b859bb5e7df6d3dbad35d01332e4d2237810b941";

    @ParameterizedTest
    @MethodSource("ineligibleStatusRiskPairs")
    void rejectsGreenAndContradictoryStatusRiskPairs(CheckStatus status, RiskLevel risk) {
        Fixture fixture = fixture(List.of(result(RESULT_ID, TASK_ID, status, risk, 7)));

        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "key-ineligible"), user("user-1")),
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertThat(fixture.acknowledgements.findByCheckResultId(RESULT_ID)).isEmpty();
        assertThat(fixture.audits).isEmpty();
    }

    static List<Arguments> ineligibleStatusRiskPairs() {
        return List.of(
                Arguments.of(CheckStatus.PASS, RiskLevel.GREEN),
                Arguments.of(CheckStatus.PASS, RiskLevel.YELLOW),
                Arguments.of(CheckStatus.PASS, RiskLevel.RED),
                Arguments.of(CheckStatus.NOT_EVALUATED, RiskLevel.GREEN),
                Arguments.of(CheckStatus.NOT_EVALUATED, RiskLevel.RED),
                Arguments.of(CheckStatus.FAIL, RiskLevel.GREEN),
                Arguments.of(CheckStatus.FAIL, RiskLevel.YELLOW));
    }

    @ParameterizedTest
    @MethodSource("eligibleStatusRiskPairs")
    void acknowledgesOnlyTheTwoEligibleNonGreenPairs(CheckStatus status, RiskLevel risk) {
        Fixture fixture = fixture(List.of(result(RESULT_ID, TASK_ID, status, risk, 7)));

        FindingAcknowledgementView view = fixture.service.acknowledge(
                command(RESULT_ID, "key-eligible-" + status), user("user-1"));

        assertThat(view.checkResultId()).isEqualTo(RESULT_ID);
        assertThat(view.acknowledgedBy()).isEqualTo("user-1");
        assertThat(view.acknowledgedAt()).isEqualTo(NOW);
        assertThat(view.ruleVersion()).isEqualTo(7);
    }

    static List<Arguments> eligibleStatusRiskPairs() {
        return List.of(
                Arguments.of(CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW),
                Arguments.of(CheckStatus.FAIL, RiskLevel.RED));
    }

    @Test
    void missingResultIsNotFound() {
        Fixture fixture = fixture(List.of());

        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "key-missing-result"), user("user-1")),
                BusinessErrorCode.NOT_FOUND);
    }

    @Test
    void missingTaskIsNotFound() {
        AuditCheckResult result = result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7);
        AuditCheckResultRepository results = resultRepository(result);
        AuditTaskRepository tasks = taskRepository(Optional.empty());
        Fixture fixture = fixture(results, tasks, new InMemoryIdempotencyAdapter());

        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "key-missing-task"), user("user-1")),
                BusinessErrorCode.NOT_FOUND);
    }

    @Test
    void mismatchedSemanticResultOrTaskIsNotFound() {
        AuditCheckResult wrongResult = result(
                SECOND_RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7);
        Fixture wrongResultFixture = fixture(
                resultRepository(wrongResult), taskRepository(Optional.of(task(TASK_ID))),
                new InMemoryIdempotencyAdapter());

        assertBusinessError(
                () -> wrongResultFixture.service.acknowledge(
                        command(RESULT_ID, "key-wrong-result"), user("user-1")),
                BusinessErrorCode.NOT_FOUND);

        AuditTask wrongTask = task(UUID.fromString("20000000-0000-0000-0000-000000000099"));
        Fixture wrongTaskFixture = fixture(
                resultRepository(result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)),
                taskRepository(Optional.of(wrongTask)), new InMemoryIdempotencyAdapter());

        assertBusinessError(
                () -> wrongTaskFixture.service.acknowledge(
                        command(RESULT_ID, "key-wrong-task"), user("user-1")),
                BusinessErrorCode.NOT_FOUND);
    }

    @Test
    void sameKeyAndFingerprintReplaysTheOriginalDurableAcknowledgement() {
        Fixture fixture = fixture(List.of(result(
                RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)));
        AcknowledgeFindingCommand command = command(RESULT_ID, "same-key");

        FindingAcknowledgementView original = fixture.service.acknowledge(command, user("user-1"));
        FindingAcknowledgementView replay = fixture.service.acknowledge(command, user("user-1"));

        assertThat(replay).isEqualTo(original);
        assertThat(fixture.audits).hasSize(1);
        assertThat(fixture.idempotency.size()).isEqualTo(1);
    }

    @Test
    void changedFingerprintForTheSameKeyIsConflict() {
        Fixture fixture = fixture(List.of(
                result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7),
                result(SECOND_RESULT_ID, TASK_ID, CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW, 8)));
        fixture.service.acknowledge(command(RESULT_ID, "reused-key"), user("user-1"));

        assertBusinessError(
                () -> fixture.service.acknowledge(command(SECOND_RESULT_ID, "reused-key"), user("user-1")),
                BusinessErrorCode.CONFLICT);
        assertThat(fixture.acknowledgements.findByCheckResultId(SECOND_RESULT_ID)).isEmpty();
    }

    @Test
    void anotherKeyOrCompetingUserCannotRewriteTheOriginalActor() {
        Fixture fixture = fixture(List.of(result(
                RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)));
        FindingAcknowledgementView original = fixture.service.acknowledge(
                command(RESULT_ID, "winning-key"), user("user-1"));

        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "another-key"), user("user-1")),
                BusinessErrorCode.CONFLICT);
        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "winning-key"), user("user-2")),
                BusinessErrorCode.CONFLICT);

        FindingAcknowledgement winner = fixture.acknowledgements.findByCheckResultId(RESULT_ID).orElseThrow();
        assertThat(winner.id()).isEqualTo(original.id());
        assertThat(winner.acknowledgedBy()).isEqualTo("user-1");
        assertThat(fixture.audits).singleElement().extracting(OperationAuditEvent::userId)
                .isEqualTo("user-1");
    }

    @ParameterizedTest
    @MethodSource("nonReplayStatuses")
    void inProgressOrFailedSameFingerprintClaimIsConflict(IdempotencyStatus status) {
        PresetClaimIdempotencyPort preset = new PresetClaimIdempotencyPort(status);
        Fixture fixture = fixture(
                resultRepository(result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)),
                taskRepository(Optional.of(task(TASK_ID))), preset);

        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "pending-key"), user("user-1")),
                BusinessErrorCode.CONFLICT);
        assertThat(fixture.audits).isEmpty();
    }

    static List<IdempotencyStatus> nonReplayStatuses() {
        return List.of(IdempotencyStatus.IN_PROGRESS, IdempotencyStatus.FAILED);
    }

    @Test
    void fingerprintHasAStableActorResultTaskAndRuleVersionVector() {
        CapturingIdempotencyPort capturing = new CapturingIdempotencyPort();
        Fixture fixture = fixture(
                resultRepository(result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)),
                taskRepository(Optional.of(task(TASK_ID))), capturing);

        fixture.service.acknowledge(command(RESULT_ID, "fingerprint-key"), user("user-1"));

        assertThat(capturing.request.get().requestFingerprint()).isEqualTo(EXPECTED_FINGERPRINT);
        assertThat(capturing.request.get().resultResourceId()).isNotBlank();
    }

    @Test
    void auditPayloadUsesExactSemanticIdentitiesAndExcludesSensitiveFindingContent() {
        AuditCheckResult hostile = new AuditCheckResult(
                RESULT_ID, TASK_ID, "RULE_TOKEN_COOKIE", "item-key", CheckStatus.FAIL, RiskLevel.RED,
                true, "actual-secret-token", "expected-Cookie-Authorization", "raw exception /storage/path",
                7, NOW.minusSeconds(10));
        Fixture fixture = fixture(List.of(hostile));

        fixture.service.acknowledge(
                command(RESULT_ID, "idempotency-secret-key"), user("actor-string-42"));

        OperationAuditEvent event = fixture.audits.get(0);
        assertThat(event.eventType()).isEqualTo("REVIEW_FINDING_ACKNOWLEDGED");
        assertThat(event.module()).isEqualTo("REVIEW");
        assertThat(event.resourceType()).isEqualTo("AUDIT_CHECK_RESULT");
        assertThat(event.resourceId()).isEqualTo(RESULT_ID.toString());
        assertThat(event.taskId()).isEqualTo(TASK_ID.toString());
        assertThat(event.userId()).isEqualTo("actor-string-42");
        assertThat(event.requestId()).isEqualTo("request-1");
        assertThat(event.traceId()).isEqualTo("trace-1");
        String safeText = String.join("|",
                nullable(event.businessResult()), nullable(event.actionDesc()), nullable(event.detail()));
        assertThat(safeText)
                .contains("FAIL", "RED", "7")
                .doesNotContain(
                        hostile.detail(), hostile.actualValue(), hostile.expectedValue(), hostile.ruleCode(),
                        "idempotency-secret-key", "Cookie", "Session", "CSRF", "Authorization",
                        "credential", "token", "/storage/path", "raw exception");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void flowDebugUsesOnlyBoundedSemanticFieldsAcrossSuccessAndConflict() {
        Fixture fixture = fixture(List.of(result(
                RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)));
        fixture.service.acknowledge(command(RESULT_ID, "hidden-key"), user("user-1"));
        assertBusinessError(
                () -> fixture.service.acknowledge(command(RESULT_ID, "competing-hidden-key"), user("user-2")),
                BusinessErrorCode.CONFLICT);

        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> fields = ArgumentCaptor.forClass(Map.class);
        verify(fixture.flowDebug, atLeast(4)).log(
                events.capture(), eq("REVIEW"), eq("APPLICATION"), eq("acknowledgeFinding"), fields.capture());

        assertThat(events.getAllValues()).contains(
                "REVIEW_ACK_START", "REVIEW_ACK_TX_ENTER", "REVIEW_ACK_TX_COMMIT", "REVIEW_ACK_CONFLICT");
        for (Map<String, ?> fieldMap : (List<Map<String, ?>>) (List<?>) fields.getAllValues()) {
            assertThat(fieldMap.keySet()).isSubsetOf(
                    "checkResultId", "taskId", "status", "risk", "ruleVersion", "outcome");
            String rendered = fieldMap.toString();
            assertThat(rendered).doesNotContain(
                    "hidden-key", "detail", "source", "Cookie", "Session", "CSRF", "Authorization",
                    "credential", "token", "exception", "path", "storage");
        }
    }

    @Test
    void taskReadResolvesDisplayNamesLiveAndUsesTheFixedFallback() {
        AtomicReference<Map<String, String>> names = new AtomicReference<>(Map.of("user-1", "First Name"));
        Fixture fixture = fixture(
                List.of(result(RESULT_ID, TASK_ID, CheckStatus.FAIL, RiskLevel.RED, 7)),
                id -> Optional.ofNullable(names.get().get(id)));
        fixture.service.acknowledge(command(RESULT_ID, "read-key"), user("user-1"));

        assertThat(fixture.service.findByTaskId(TASK_ID))
                .singleElement()
                .extracting(FindingAcknowledgementView::acknowledgedByDisplayName)
                .isEqualTo("First Name");
        names.set(Map.of());
        assertThat(fixture.service.findByTaskId(TASK_ID))
                .singleElement()
                .extracting(FindingAcknowledgementView::acknowledgedByDisplayName)
                .isEqualTo(FindingAcknowledgementView.UNKNOWN_OR_DELETED_USER_DISPLAY_NAME);
    }

    private Fixture fixture(List<AuditCheckResult> results) {
        return fixture(results, id -> Optional.of("Display " + id));
    }

    private Fixture fixture(
            List<AuditCheckResult> results,
            com.fuquan.audit.security.application.port.UserIdentityQueryPort identities) {
        InMemoryAuditCheckResultRepository resultRepository = new InMemoryAuditCheckResultRepository();
        if (!results.isEmpty()) {
            resultRepository.appendAll(results);
        }
        InMemoryAuditTaskRepository taskRepository = new InMemoryAuditTaskRepository();
        taskRepository.create(task(TASK_ID));
        return fixture(resultRepository, taskRepository, new InMemoryIdempotencyAdapter(), identities);
    }

    private Fixture fixture(
            AuditCheckResultRepository results,
            AuditTaskRepository tasks,
            IdempotencyPort idempotency) {
        return fixture(results, tasks, idempotency, id -> Optional.of("Display " + id));
    }

    private Fixture fixture(
            AuditCheckResultRepository results,
            AuditTaskRepository tasks,
            IdempotencyPort idempotency,
            com.fuquan.audit.security.application.port.UserIdentityQueryPort identities) {
        InMemoryFindingAcknowledgementRepository acknowledgements =
                new InMemoryFindingAcknowledgementRepository(results);
        List<OperationAuditEvent> audits = new ArrayList<>();
        FlowDebugLogger flowDebug = mock(FlowDebugLogger.class);
        FindingAcknowledgementTransactionCoordinator coordinator =
                new FindingAcknowledgementTransactionCoordinator(
                        acknowledgements, idempotency, audits::add, flowDebug);
        FindingAcknowledgementService service = new FindingAcknowledgementService(
                results, tasks, acknowledgements, acknowledgements, identities, coordinator,
                Clock.fixed(NOW, ZoneOffset.UTC), flowDebug);
        return new Fixture(
                service, acknowledgements, audits, flowDebug,
                idempotency instanceof InMemoryIdempotencyAdapter memory ? memory : null);
    }

    private AuditCheckResultRepository resultRepository(AuditCheckResult result) {
        return new AuditCheckResultRepository() {
            @Override
            public void appendAll(List<AuditCheckResult> results) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AuditCheckResult> findByTaskId(UUID taskId) {
                return List.of(result);
            }

            @Override
            public Optional<AuditCheckResult> findById(UUID id) {
                return Optional.of(result);
            }
        };
    }

    private AuditTaskRepository taskRepository(Optional<AuditTask> task) {
        return new AuditTaskRepository() {
            @Override
            public AuditTask create(AuditTask newTask) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AuditTask> findById(UUID id) {
                return task;
            }

            @Override
            public Optional<AuditTask> findLatest(String sourceType, UUID sourceId, String reviewType) {
                return Optional.empty();
            }

            @Override
            public AuditTask transition(
                    UUID id, AuditTaskStatus expectedStatus, long expectedVersion,
                    AuditTaskStatus targetStatus, Instant occurredAt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AuditCheckResult result(
            UUID id, UUID taskId, CheckStatus status, RiskLevel risk, long ruleVersion) {
        return new AuditCheckResult(
                id, taskId, "F02_TEST", "item-1", status, risk, risk == RiskLevel.RED,
                "actual", "expected", "detail", ruleVersion, NOW.minusSeconds(10));
    }

    private AuditTask task(UUID id) {
        return new AuditTask(
                id, "CONTRACT", UUID.fromString("30000000-0000-0000-0000-000000000001"),
                1, "F02_DETERMINISTIC", AuditTaskStatus.DONE, "creator", "task-request", "task-trace",
                NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.minusSeconds(10), 2);
    }

    private AcknowledgeFindingCommand command(UUID resultId, String key) {
        return new AcknowledgeFindingCommand(resultId, key, "request-1", "trace-1");
    }

    private CurrentUser user(String actor) {
        return new CurrentUser(actor, "username-" + actor, "Display " + actor, Set.of("AUDITOR"));
    }

    private void assertBusinessError(Runnable invocation, BusinessErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private record Fixture(
            FindingAcknowledgementService service,
            InMemoryFindingAcknowledgementRepository acknowledgements,
            List<OperationAuditEvent> audits,
            FlowDebugLogger flowDebug,
            InMemoryIdempotencyAdapter idempotency) {
    }

    private static final class CapturingIdempotencyPort implements IdempotencyPort {
        private final InMemoryIdempotencyAdapter delegate = new InMemoryIdempotencyAdapter();
        private final AtomicReference<IdempotencyRequest> request = new AtomicReference<>();

        @Override
        public IdempotencyClaim claim(IdempotencyRequest request) {
            this.request.set(request);
            return delegate.claim(request);
        }

        @Override
        public IdempotencyRecord markSucceeded(
                IdempotencyRecord record, String type, String id, Instant completedAt) {
            return delegate.markSucceeded(record, type, id, completedAt);
        }

        @Override
        public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
            return delegate.markFailed(record, completedAt);
        }

        @Override
        public void rollbackUncommittedClaim(IdempotencyRecord record) {
            delegate.rollbackUncommittedClaim(record);
        }
    }

    private static final class PresetClaimIdempotencyPort implements IdempotencyPort {
        private final IdempotencyStatus status;

        private PresetClaimIdempotencyPort(IdempotencyStatus status) {
            this.status = status;
        }

        @Override
        public IdempotencyClaim claim(IdempotencyRequest request) {
            return new IdempotencyClaim(false, new IdempotencyRecord(
                    UUID.fromString("40000000-0000-0000-0000-000000000001"),
                    request.scope(), request.idempotencyKey(), request.actorId(), request.resourceType(),
                    request.resourceId(), request.requestFingerprint(), status, request.resultResourceType(),
                    request.resultResourceId(), request.firstRequestId(), request.traceId(), request.createdAt(),
                    status == IdempotencyStatus.IN_PROGRESS ? null : request.createdAt(),
                    status == IdempotencyStatus.IN_PROGRESS ? 0 : 1));
        }

        @Override
        public IdempotencyRecord markSucceeded(
                IdempotencyRecord record, String type, String id, Instant completedAt) {
            throw new AssertionError("markSucceeded must not run for a duplicate claim");
        }

        @Override
        public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
            throw new AssertionError("markFailed must not run");
        }
    }
}
