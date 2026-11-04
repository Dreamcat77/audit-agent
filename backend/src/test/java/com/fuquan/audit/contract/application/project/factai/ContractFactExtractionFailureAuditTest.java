package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.*;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractFactExtractionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ContractFactExtractionFailureAuditTest {
    private static final UUID PROJECT = UUID.fromString("35000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("35000000-0000-0000-0000-000000000002");
    private static final UUID PARSE = UUID.fromString("35000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser("actor-1", "actor", "Actor", Set.of("AUDITOR"));
    private static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    private static final Provenance PROVENANCE = new Provenance("provider", "model", "revision",
            "contract-fact-prompt-v2", "contract-fact-candidates-v2");
    private static final String TEXT = "合同金额67000元\n" + "x".repeat(7680);

    @Test
    void secondBatchTimeoutRetainsProvedFirstBatchAuditWithoutPublishingFactsOrRetrying() {
        exerciseFailure(false, "TIMEOUT", 2);
    }

    @Test
    void betweenBatchBudgetExpiryRetainsProvedFirstBatchAuditWithoutAnotherCallOrFacts() {
        exerciseFailure(true, "TOTAL_BUDGET_EXCEEDED", 1);
    }

    private static void exerciseFailure(boolean expireBudget, String reason, int sentCount) {
        var attempts = new InMemoryContractFactExtractionRepository();
        var extractions = new InMemoryProjectRepositories().extractionResultRepository();
        var elapsed = new AtomicLong();
        var sent = new ArrayList<Request>();
        ContractFactCandidatePort port = new ContractFactCandidatePort() {
            @Override public Provenance configuredProvenance() { return PROVENANCE; }
            @Override public Result extract(Request request) {
                sent.add(request);
                if (sent.size() != 1) {
                    throw new ContractFactCandidateException(ContractFactCandidateException.Reason.TIMEOUT, null);
                }
                assertThat(request.scope()).isEqualTo(Scope.CORE);
                assertThat(request.batchOrdinal()).isEqualTo(1);
                assertThat(request.batchCount()).isEqualTo(2);
                var evidence = request.segments().get(0);
                var candidate = new Candidate("cfc-v2-" + "a".repeat(64), FactType.CONTRACT_AMOUNT, null,
                        null, new BigDecimal("67000"), evidence.segmentId(), evidence.sourceLocator(),
                        evidence.text(), 0, evidence.endOffset() - evidence.startOffset(),
                        ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.99"));
                if (expireBudget) elapsed.set(1_000_000_000L);
                return new Result(List.of(candidate), PROVENANCE, 30,
                        List.of(new Rejection(2, RejectionStage.TYPED_SCHEMA,
                                "INVALID_TYPED_SCHEMA", "b".repeat(64))), BatchStatus.PARTIAL, Scope.CORE,
                        List.of(new ModelCall(1, CallPhase.TRANSPORT, CallOutcome.FAILED,
                                        "INVALID_TYPED_SCHEMA", 10, null, 10, 4, 14),
                                new ModelCall(2, CallPhase.REPAIR, CallOutcome.SUCCEEDED,
                                        null, 20, "STOP", 10, 4, 14)));
            }
        };
        var authority = new ContractFactExtractionAuthority(
                new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "f".repeat(64), PARSE,
                        2, sha256(TEXT), TEXT, DocumentRole.CONTRACT_BODY, true),
                UUID.fromString("35000000-0000-0000-0000-000000000004"),
                UUID.fromString("35000000-0000-0000-0000-000000000005"));
        var transactions = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                (projectId, fileId) -> authority, port);
        var service = new ContractFactExtractionService(port, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                expireBudget ? 1000 : 900000, elapsed::get);

        assertThatThrownBy(() -> service.extract(PROJECT, FILE, "failure-audit", ACTOR, META))
                .isInstanceOf(ContractFactCandidateException.class)
                .extracting(failure -> ((ContractFactCandidateException) failure).getReason().name()).isEqualTo(reason);
        var terminal = attempts.findByOperation(ACTOR.userId(), "failure-audit").orElseThrow();
        assertThat(terminal.status()).isEqualTo(ContractFactExtractionAttempt.Status.OUTCOME_UNKNOWN);
        assertThat(terminal.failureReason()).isEqualTo(reason);
        assertThat(terminal.acceptedCount()).isZero();
        assertThat(extractions.findByProjectId(PROJECT)).isEmpty();
        assertThat(attempts.findCandidates(terminal.attemptId())).isEmpty();
        assertThat(attempts.findRejections(terminal.attemptId())).singleElement().satisfies(rejection -> {
            assertThat(rejection.providerOrdinal()).isEqualTo(2);
            assertThat(rejection.scope()).isEqualTo(Scope.CORE);
            assertThat(rejection.reasonCode()).isEqualTo("INVALID_TYPED_SCHEMA");
        });
        var audit = attempts.findModelCalls(terminal.attemptId());
        assertThat(audit).hasSize(2).extracting(ContractModelCallAudit::callOrdinal).containsExactly(1, 2);
        assertThat(audit).allSatisfy(call -> {
            assertThat(call.scope()).isEqualTo(Scope.CORE);
            assertThat(call.batchStatus()).isEqualTo(BatchStatus.PARTIAL);
            assertThat(call.requestFingerprint()).matches("[0-9a-f]{64}");
        });
        assertThat(audit.get(0).requestFingerprint()).isEqualTo(audit.get(1).requestFingerprint());
        assertThat(sent).hasSize(sentCount);
        assertThatThrownBy(() -> service.extract(PROJECT, FILE, "failure-audit", ACTOR, META))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(sent).hasSize(sentCount);
        assertThat(attempts.findById(terminal.attemptId())).contains(terminal);
        assertThat(attempts.findModelCalls(terminal.attemptId())).isEqualTo(audit);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
