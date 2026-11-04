package com.fuquan.audit.contract.infra.memory.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRejection;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.RejectionStage;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryContractFactExtractionRepositoryTest {
    private static final UUID PROJECT = UUID.fromString("36500000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("36500000-0000-0000-0000-000000000002");
    private static final UUID PARSE = UUID.fromString("36500000-0000-0000-0000-000000000003");
    private static final Provenance PROVENANCE = new Provenance("provider", "model", "revision",
            "prompt", "schema");

    @Test
    void durableClaimIsScopedByActorAndOperationKeyWhileFingerprintOnlyGuardsReplayInput() {
        var repository = new InMemoryContractFactExtractionRepository();
        var first = attempt("actor", "operation-1", "a".repeat(64));

        assertThat(repository.claim(first).acquired()).isTrue();
        var sameOperationChangedInput = repository.claim(
                attempt("actor", "operation-1", "b".repeat(64)));
        assertThat(sameOperationChangedInput.acquired()).isFalse();
        assertThat(sameOperationChangedInput.attempt().inputFingerprint()).isEqualTo("a".repeat(64));
        assertThat(repository.claim(attempt("actor", "operation-2", "a".repeat(64))).acquired()).isTrue();
    }

    @Test
    void completesRejectionsAndModelCallsWithTheAttemptAtomically() {
        var repository = new InMemoryContractFactExtractionRepository();
        var current = repository.claim(attempt("actor", "operation-v2", "a".repeat(64))).attempt();
        Instant completedAt = Instant.parse("2026-09-25T00:00:00Z");
        var rejection = new ContractFactExtractionRejection(UUID.randomUUID(), current.attemptId(), 1,
                Scope.CORE, 2, RejectionStage.TYPED_SCHEMA, "INVALID_TYPED_SCHEMA",
                "d".repeat(64), completedAt);
        var call = new ContractModelCallAudit(UUID.randomUUID(), current.attemptId(), null, 1,
                Scope.CORE, 1, CallPhase.TRANSPORT, "e".repeat(64), "provider", "model",
                CallOutcome.SUCCEEDED, null, 9, "STOP", 10, 5, 15,
                BatchStatus.PARTIAL, completedAt);

        repository.completeV2(current, java.util.List.of(), java.util.List.of(rejection),
                java.util.List.of(call), 0, 1, completedAt);

        assertThat(repository.findRejections(current.attemptId())).containsExactly(rejection);
        assertThat(repository.findModelCalls(current.attemptId())).containsExactly(call);
        assertThat(repository.findById(current.attemptId()).orElseThrow().rejectedCount()).isEqualTo(1);
    }

    @Test
    void failsAttemptWithoutLosingAuditableProviderCalls() {
        var repository = new InMemoryContractFactExtractionRepository();
        var current = repository.claim(attempt("actor", "operation-failed-v2", "a".repeat(64))).attempt();
        Instant failedAt = Instant.parse("2026-09-25T00:01:00Z");
        var call = new ContractModelCallAudit(UUID.randomUUID(), current.attemptId(), null, 1,
                Scope.LINE_ITEMS, 2, CallPhase.REPAIR, "e".repeat(64), "provider", "model",
                CallOutcome.FAILED, "INVALID_PROVIDER_JSON", 9, null, null, null, null,
                BatchStatus.FAILED, failedAt);

        repository.failV2(current, java.util.List.of(), java.util.List.of(call),
                0, "ALL_SCOPES_FAILED", failedAt);

        assertThat(repository.findModelCalls(current.attemptId())).containsExactly(call);
        assertThat(repository.findById(current.attemptId()).orElseThrow()).satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(ContractFactExtractionAttempt.Status.FAILED);
            assertThat(attempt.failureReason()).isEqualTo("ALL_SCOPES_FAILED");
        });
    }

    private static ContractFactExtractionAttempt attempt(String actor, String operation, String fingerprint) {
        return ContractFactExtractionAttempt.start(UUID.randomUUID(), PROJECT, FILE, "f".repeat(64),
                PARSE, 1, "c".repeat(64), DocumentRole.CONTRACT_BODY,
                UUID.fromString("36500000-0000-0000-0000-000000000004"),
                UUID.fromString("36500000-0000-0000-0000-000000000005"),
                fingerprint, actor, operation, PROVENANCE, Instant.parse("2026-09-24T00:00:00Z"));
    }

    @Test
    void unknownOutcomeCannotBecomeFailedOrAcquireAnotherCallOnReplay() {
        var repository = new InMemoryContractFactExtractionRepository();
        var proposed = attempt("actor", "unknown", "a".repeat(64));
        var current = repository.claim(proposed).attempt();
        repository.failV2(current, java.util.List.of(), java.util.List.of(), 0,
                "MODEL_OUTCOME_UNKNOWN", current.createdAt().plusSeconds(1));
        var replay = repository.claim(proposed);
        assertThat(replay.acquired()).isFalse();
        assertThat(replay.attempt().status()).isEqualTo(ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN);
        assertThat(replay.attempt().attemptId()).isEqualTo(current.attemptId());
        assertThat(replay.attempt().callCount()).isEqualTo(1);
    }
}
