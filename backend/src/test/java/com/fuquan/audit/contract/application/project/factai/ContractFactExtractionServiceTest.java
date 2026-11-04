package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionTransactionCoordinator.Prepared;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractFactExtractionServiceTest {
    private static final UUID PROJECT = UUID.fromString("35000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("35000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = UUID.fromString("35000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser("actor-1", "actor", "Actor", Set.of("AUDITOR"));
    private static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");

    private final ContractFactCandidatePort port = mock(ContractFactCandidatePort.class);
    private final ContractFactExtractionTransactionCoordinator transactions =
            mock(ContractFactExtractionTransactionCoordinator.class);
    private final ContractFactExtractionService service = new ContractFactExtractionService(
            port, transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void callsProviderOutsideCoordinatorAndCompletesThePreparedAttempt() {
        Request request = request();
        Prepared prepared = new Prepared(ATTEMPT, request, false,
                new ContractFactExtractionResult(ATTEMPT, false, "IN_PROGRESS", 0, 0));
        Result provider = new Result(List.of(), new Provenance("provider", "model", "revision",
                "contract-fact-prompt-v1", "contract-fact-candidates-v1"), 12);
        var completed = new ContractFactExtractionResult(ATTEMPT, false, "SUCCEEDED", 0, 0);
        when(transactions.prepare(PROJECT, FILE, "operation-key", ACTOR, META, NOW)).thenReturn(prepared);
        when(port.extract(org.mockito.ArgumentMatchers.any())).thenReturn(provider);
        when(transactions.complete(prepared, provider, NOW)).thenReturn(completed);

        assertThat(service.extract(PROJECT, FILE, "operation-key", ACTOR, META)).isEqualTo(completed);

        verify(port).extract(org.mockito.ArgumentMatchers.any());
        verify(transactions).complete(prepared, provider, NOW);
    }

    @Test
    void completedAttemptReplaysWithoutAnotherProviderCall() {
        Request request = request();
        var replay = new ContractFactExtractionResult(ATTEMPT, true, "SUCCEEDED", 3, 1);
        when(transactions.prepare(PROJECT, FILE, "operation-key", ACTOR, META, NOW))
                .thenReturn(new Prepared(ATTEMPT, request, true, replay));

        assertThat(service.extract(PROJECT, FILE, "operation-key", ACTOR, META)).isEqualTo(replay);

        verify(port, never()).extract(request);
    }

    @Test
    void providerFailureIsDurablyClosedBeforeItEscapes() {
        Request request = request();
        Prepared prepared = new Prepared(ATTEMPT, request, false,
                new ContractFactExtractionResult(ATTEMPT, false, "IN_PROGRESS", 0, 0));
        var failure = new ContractFactCandidateException(
                ContractFactCandidateException.Reason.TIMEOUT, new RuntimeException("timeout"));
        when(transactions.prepare(PROJECT, FILE, "operation-key", ACTOR, META, NOW)).thenReturn(prepared);
        when(port.extract(org.mockito.ArgumentMatchers.any())).thenThrow(failure);

        assertThatThrownBy(() -> service.extract(PROJECT, FILE, "operation-key", ACTOR, META))
                .isSameAs(failure);

        verify(transactions).fail(prepared, "TIMEOUT", NOW);
    }

    @Test
    void oneGlobalBudgetStopsLaterBatchesAndDoesNotResetAfterFirstCall() {
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        var bounded = new ContractFactExtractionService(port, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), 1000, elapsed::get);
        Request request = request();
        Prepared prepared = new Prepared(ATTEMPT, List.of(request, request), false,
                new ContractFactExtractionResult(ATTEMPT, false, "IN_PROGRESS", 0, 0));
        when(transactions.prepare(PROJECT, FILE, "operation-key", ACTOR, META, NOW)).thenReturn(prepared);
        when(port.extract(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            assertThat(((Request) call.getArgument(0)).remainingBudgetMs()).isEqualTo(1000);
            elapsed.set(1_000_000_000L);
            return new Result(List.of(), new Provenance("provider", "model", "revision", "prompt", "schema"), 1);
        });
        assertThatThrownBy(() -> bounded.extract(PROJECT, FILE, "operation-key", ACTOR, META))
                .isInstanceOf(ContractFactCandidateException.class).extracting("reason")
                .isEqualTo(ContractFactCandidateException.Reason.TOTAL_BUDGET_EXCEEDED);
        verify(port, org.mockito.Mockito.times(1)).extract(org.mockito.ArgumentMatchers.any());
        verify(transactions).fail(org.mockito.ArgumentMatchers.eq(prepared),
                org.mockito.ArgumentMatchers.argThat(results -> results.size() == 1),
                org.mockito.ArgumentMatchers.eq("TOTAL_BUDGET_EXCEEDED"), org.mockito.ArgumentMatchers.eq(NOW));
    }

    private static Request request() {
        return new Request(PROJECT, FILE, "a".repeat(64), ATTEMPT, 1, "b".repeat(64), List.of());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ContractFactCandidatePort.BatchStatus.class, names = {"FAILED", "UNKNOWN"})
    void terminalBatchStopsLaterCallsAndPreservesItsKnownAudit(ContractFactCandidatePort.BatchStatus status) {
        Request request = request();
        Prepared prepared = new Prepared(ATTEMPT, List.of(request, request), false,
                new ContractFactExtractionResult(ATTEMPT, false, "IN_PROGRESS", 0, 0));
        var provider = new Result(List.of(), new Provenance("provider", "model", "revision", "prompt", "schema"),
                1, List.of(), status, ContractFactCandidatePort.Scope.CORE, List.of());
        when(transactions.prepare(PROJECT, FILE, "operation-key", ACTOR, META, NOW)).thenReturn(prepared);
        when(port.extract(org.mockito.ArgumentMatchers.any())).thenReturn(provider);
        when(transactions.complete(prepared, List.of(provider, provider), NOW)).thenReturn(
                new ContractFactExtractionResult(ATTEMPT, false, status == ContractFactCandidatePort.BatchStatus.UNKNOWN
                        ? "OUTCOME_UNKNOWN" : "FAILED", 0, 0));

        var result = service.extract(PROJECT, FILE, "operation-key", ACTOR, META);

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.status()).isEqualTo(status == ContractFactCandidatePort.BatchStatus.UNKNOWN ? "OUTCOME_UNKNOWN" : "FAILED");
        verify(port, org.mockito.Mockito.times(1)).extract(org.mockito.ArgumentMatchers.any());
        verify(transactions).fail(prepared, List.of(provider),
                status == ContractFactCandidatePort.BatchStatus.UNKNOWN ? "MODEL_OUTCOME_UNKNOWN" : "MODEL_BATCH_FAILED", NOW);
        verify(transactions, never()).complete(org.mockito.ArgumentMatchers.eq(prepared),
                org.mockito.ArgumentMatchers.<List<Result>>any(), org.mockito.ArgumentMatchers.any());
    }
}
