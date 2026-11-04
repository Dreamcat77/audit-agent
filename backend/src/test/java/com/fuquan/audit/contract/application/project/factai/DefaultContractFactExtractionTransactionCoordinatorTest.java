package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Candidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.ContextCode;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.FactType;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.ModelCall;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultContractFactExtractionTransactionCoordinatorTest {
    private static final UUID PROJECT = UUID.fromString("36000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("36000000-0000-0000-0000-000000000002");
    private static final UUID PARSE = UUID.fromString("36000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");
    private static final String TEXT = "合同金额67000元";
    private static final Provenance PROVENANCE = new Provenance("provider", "model", "revision",
            "contract-fact-prompt-v1", "contract-fact-candidates-v1");
    private static final CurrentUser ACTOR = new CurrentUser("actor-1", "actor", "Actor", Set.of("AUDITOR"));

    private final ContractFactExtractionRepository attempts = mock(ContractFactExtractionRepository.class);
    private final ExtractionResultRepository extractions = mock(ExtractionResultRepository.class);
    private final ContractFactExtractionAuthorityResolver authority = mock(ContractFactExtractionAuthorityResolver.class);
    private final ContractFactCandidatePort port = mock(ContractFactCandidatePort.class);
    private final AtomicReference<ContractFactExtractionAttempt> stored = new AtomicReference<>();
    private DefaultContractFactExtractionTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(port.configuredProvenance()).thenReturn(PROVENANCE);
        when(authority.resolve(PROJECT, FILE)).thenReturn(authority());
        when(attempts.claim(any())).thenAnswer(invocation -> {
            ContractFactExtractionAttempt proposed = invocation.getArgument(0);
            stored.set(proposed);
            return new ContractFactExtractionRepository.Claim(true, proposed);
        });
        when(attempts.findById(any())).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(extractions.insertIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        coordinator = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                authority, port, new ContractFactSegmenter(),
                new ContractFactCandidateValidator(PROVENANCE));
    }

    @Test
    void publishesOnlyAcceptedCandidatesAndCompletesDurableCounts() {
        var prepared = coordinator.prepare(PROJECT, FILE, "operation", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        var segment = prepared.request().segments().get(0);
        Candidate accepted = new Candidate("cfc-v1-" + "a".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                "67000", 4, 9, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.99"));
        Candidate rejected = new Candidate("cfc-v1-" + "b".repeat(64), FactType.AWARD_AMOUNT,
                null, null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                "67000", 4, 9, ContextCode.AWARD_FINAL_RESULT, new BigDecimal("0.80"));

        var result = coordinator.complete(prepared,
                new Result(List.of(accepted, rejected), PROVENANCE, 20), NOW.plusSeconds(1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(1);
        verify(extractions).insertIfAbsent(any());
        ArgumentCaptor<List<ContractFactExtractionCandidate>> rows = ArgumentCaptor.forClass(List.class);
        verify(attempts).complete(any(), rows.capture(), anyInt(), anyInt(), any());
        assertThat(rows.getValue()).extracting(ContractFactExtractionCandidate::validationStatus)
                .containsExactly("ACCEPTED", "REJECTED");
        assertThat(rows.getValue().get(0).publishedExtractionId()).isNotNull();
        assertThat(rows.getValue().get(1).publishedExtractionId()).isNull();
    }

    @Test
    void succeededInputReplaysWithoutCreatingAnotherAttempt() {
        org.mockito.Mockito.doAnswer(invocation -> {
            ContractFactExtractionAttempt proposed = invocation.getArgument(0);
            ContractFactExtractionAttempt succeeded = proposed.completed(
                    ContractFactExtractionAttempt.Status.SUCCEEDED, 2, 1, null, NOW.plusSeconds(1));
            return new ContractFactExtractionRepository.Claim(false, succeeded);
        }).when(attempts).claim(any());

        var result = coordinator.prepare(PROJECT, FILE, "operation", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);

        assertThat(result.replayed()).isTrue();
        assertThat(result.currentResult().acceptedCount()).isEqualTo(2);
        verify(extractions, never()).insertIfAbsent(any());
    }

    @Test
    void segmentationBudgetFailureCreatesNoAttemptAndCallsNoProvider() {
        String oversized = "a".repeat(122881);
        ContractFactExtractionAuthority original = authority();
        var current = original.current();
        when(authority.resolve(PROJECT, FILE)).thenReturn(new ContractFactExtractionAuthority(
                new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE,
                        current.fileSha256(), current.parseResultId(), current.parseVersion(),
                        sha256(oversized), oversized, current.effectiveRole(), true),
                original.roleDecisionId(), original.identityDecisionId()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.prepare(
                PROJECT, FILE, "oversized-operation", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW))
                .isInstanceOf(ContractFactSegmentationException.class);

        verify(attempts, never()).claim(any());
        verify(port, never()).extract(any());
    }

    @Test
    void candidateLinksTheActualDeduplicatedPublishedExtractionIdentity() {
        var prepared = coordinator.prepare(PROJECT, FILE, "operation", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        var segment = prepared.request().segments().get(0);
        Candidate accepted = new Candidate("cfc-v1-" + "c".repeat(64), FactType.CONTRACT_AMOUNT,
                null, null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                "67000", 4, 9, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.99"));
        UUID durableExtractionId = UUID.fromString("36000000-0000-0000-0000-000000000099");
        when(extractions.insertIfAbsent(any())).thenAnswer(invocation -> {
            var proposed = (com.fuquan.audit.contract.domain.project.ExtractionResult) invocation.getArgument(0);
            return new com.fuquan.audit.contract.domain.project.ExtractionResult(durableExtractionId,
                    proposed.projectId(), proposed.sourceFileId(), proposed.parseResultId(),
                    proposed.candidateId(), proposed.extractionKey(), proposed.extractionVersion(),
                    proposed.factType(), proposed.groupKey(), proposed.valueText(),
                    proposed.normalizedValue(), proposed.valueAmount(), proposed.sourceText(),
                    proposed.pageNumber(), proposed.paragraphIndex(), proposed.sourceLocator(),
                    proposed.createdAt());
        });

        coordinator.complete(prepared, new Result(List.of(accepted), PROVENANCE, 20), NOW.plusSeconds(1));

        ArgumentCaptor<List<ContractFactExtractionCandidate>> rows = ArgumentCaptor.forClass(List.class);
        verify(attempts).complete(any(), rows.capture(), anyInt(), anyInt(), any());
        assertThat(rows.getValue()).singleElement().satisfies(row ->
                assertThat(row.publishedExtractionId()).isEqualTo(durableExtractionId));
    }

    @Test
    void lateProviderResponseCannotPublishAfterAuthorityDecisionChangesOnTheSameParse() {
        var prepared = coordinator.prepare(PROJECT, FILE, "operation", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        ContractFactExtractionAuthority original = authority();
        when(authority.resolve(PROJECT, FILE)).thenReturn(new ContractFactExtractionAuthority(
                original.current(), UUID.fromString("36000000-0000-0000-0000-000000000099"),
                original.identityDecisionId()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.complete(
                prepared, new Result(List.of(), PROVENANCE, 20), NOW.plusSeconds(1)))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class)
                .hasMessageContaining("durable state cannot be verified");

        verify(extractions, never()).insertIfAbsent(any());
        verify(attempts, never()).complete(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void v2SplitsScopesAndDurablyFailsOnlyAfterBothFailedCallsAreRecorded() {
        Provenance v2 = new Provenance("provider", "model", "revision",
                "contract-fact-prompt-v2", "contract-fact-candidates-v2");
        when(port.configuredProvenance()).thenReturn(v2);
        coordinator = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                authority, port, new ContractFactSegmenter(), new ContractFactCandidateValidator(v2));
        var prepared = coordinator.prepare(PROJECT, FILE, "operation-v2", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);

        assertThat(prepared.requests()).extracting(request -> request.scope())
                .containsExactly(Scope.CORE, Scope.LINE_ITEMS);
        Result core = failed(v2, Scope.CORE, 1, CallPhase.TRANSPORT);
        Result lines = failed(v2, Scope.LINE_ITEMS, 2, CallPhase.REPAIR);

        var result = coordinator.complete(prepared, List.of(core, lines), NOW.plusSeconds(1));

        assertThat(result.status()).isEqualTo("FAILED");
        ArgumentCaptor<List<ContractModelCallAudit>> calls = ArgumentCaptor.forClass(List.class);
        verify(attempts).failV2(any(), any(), calls.capture(), anyInt(),
                org.mockito.ArgumentMatchers.eq("ALL_SCOPES_FAILED"), any());
        assertThat(calls.getValue()).extracting(ContractModelCallAudit::scope)
                .containsExactly(Scope.CORE, Scope.LINE_ITEMS);
    }

    private static Result failed(Provenance provenance, Scope scope, int ordinal, CallPhase phase) {
        return new Result(List.of(), provenance, 7, List.of(), BatchStatus.FAILED, scope,
                List.of(new ModelCall(ordinal, phase, CallOutcome.FAILED,
                        "INVALID_PROVIDER_JSON", 7, null, null, null, null)));
    }

    @Test
    void completeDocumentBatchesKeepEveryScopeBoundedAndAuditOrdinalsUnique() {
        String text = ("合同条款" + "𠀀".repeat(124) + "\n").repeat(63) + "合同金额67000元";
        var original = authority();
        var input = original.current();
        when(authority.resolve(PROJECT, FILE)).thenReturn(new ContractFactExtractionAuthority(
                new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, input.fileSha256(), PARSE,
                        2, sha256(text), text, DocumentRole.CONTRACT_BODY, true),
                original.roleDecisionId(), original.identityDecisionId()));
        Provenance v2 = new Provenance("provider", "model", "revision",
                "contract-fact-prompt-v2", "contract-fact-candidates-v2");
        when(port.configuredProvenance()).thenReturn(v2);
        coordinator = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                authority, port, new ContractFactSegmenter(), new ContractFactCandidateValidator(v2));
        var prepared = coordinator.prepare(PROJECT, FILE, "long-v2", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        assertThat(prepared.requests()).hasSizeBetween(4, 256).allSatisfy(request -> {
            assertThat(request.segments()).hasSizeLessThanOrEqualTo(32);
            assertThat(request.segments().stream().mapToInt(segment ->
                    ContractFactBatchPlanner.estimatedOutputUnits(segment, request.scope())).sum())
                    .isLessThanOrEqualTo(4096 - 512);
        });
        for (Scope scope : List.of(Scope.CORE, Scope.LINE_ITEMS)) {
            assertThat(prepared.requests().stream().filter(request -> request.scope() == scope)
                    .flatMap(request -> request.segments().stream()).map(segment -> segment.text())
                    .collect(java.util.stream.Collectors.joining())).isEqualTo(text);
        }
        List<Result> results = prepared.requests().stream().map(request -> new Result(List.of(), v2, 7,
                List.of(new ContractFactCandidatePort.Rejection(1,
                        ContractFactCandidatePort.RejectionStage.TYPED_SCHEMA,
                        "INVALID_TYPED_SCHEMA", "a".repeat(64))), BatchStatus.FAILED, request.scope(),
                List.of(new ModelCall(1, CallPhase.TRANSPORT, CallOutcome.FAILED,
                        "MODEL_REFUSED", 7, null, null, null, null)))).toList();
        coordinator.complete(prepared, results, NOW.plusSeconds(1));
        ArgumentCaptor<List<ContractModelCallAudit>> calls = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ContractFactExtractionRejection>> rejections = ArgumentCaptor.forClass(List.class);
        verify(attempts).failV2(any(), rejections.capture(), calls.capture(), anyInt(), any(), any());
        assertThat(calls.getValue()).extracting(ContractModelCallAudit::callOrdinal).doesNotHaveDuplicates();
        assertThat(calls.getValue()).extracting(ContractModelCallAudit::requestFingerprint).doesNotHaveDuplicates();
        assertThat(rejections.getValue()).extracting(ContractFactExtractionRejection::providerOrdinal)
                .doesNotHaveDuplicates();
    }

    @Test
    void missingScopeCannotCompleteAnAttemptWhoseFingerprintCoversBothScopes() {
        var prepared = coordinator.prepare(PROJECT, FILE, "missing-scope", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        var incomplete = new ContractFactExtractionTransactionCoordinator.Prepared(prepared.attemptId(),
                List.of(prepared.request()), false, prepared.currentResult());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.complete(incomplete,
                List.of(new Result(List.of(), PROVENANCE, 0)), NOW.plusSeconds(1)))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        verify(extractions, never()).insertIfAbsent(any());
        verify(attempts, never()).complete(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void tailAmountInTheFinalCoreBatchIsGroundedAndPublished() {
        String text = "𠀀".repeat(8225) + "合同金额67000元";
        var original = authority();
        when(authority.resolve(PROJECT, FILE)).thenReturn(new ContractFactExtractionAuthority(
                new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "f".repeat(64), PARSE,
                        2, sha256(text), text, DocumentRole.CONTRACT_BODY, true),
                original.roleDecisionId(), original.identityDecisionId()));
        Provenance v2 = new Provenance("provider", "model", "revision",
                "contract-fact-prompt-v2", "contract-fact-candidates-v2");
        when(port.configuredProvenance()).thenReturn(v2);
        coordinator = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                authority, port, new ContractFactSegmenter(), new ContractFactCandidateValidator(v2));
        var prepared = coordinator.prepare(PROJECT, FILE, "tail-fact", ACTOR,
                new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
        List<Result> results = prepared.requests().stream().map(request -> {
            if (request.scope() != Scope.CORE || request.batchOrdinal() != request.batchCount()) {
                return new Result(List.of(), v2, 0, List.of(), BatchStatus.EMPTY, request.scope(), List.of());
            }
            var segment = request.segments().get(request.segments().size() - 1);
            var candidate = new Candidate("cfc-v2-" + "a".repeat(64), FactType.CONTRACT_AMOUNT, null, null,
                    new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(), segment.text(),
                    0, segment.endOffset() - segment.startOffset(), ContextCode.CONTRACT_AMOUNT,
                    new BigDecimal("0.99"));
            return new Result(List.of(candidate), v2, 0, List.of(), BatchStatus.PARTIAL, Scope.CORE, List.of());
        }).toList();
        var completed = coordinator.complete(prepared, results, NOW.plusSeconds(1));
        assertThat(completed.acceptedCount()).isEqualTo(1);
        ArgumentCaptor<com.fuquan.audit.contract.domain.project.ExtractionResult> published =
                ArgumentCaptor.forClass(com.fuquan.audit.contract.domain.project.ExtractionResult.class);
        verify(extractions).insertIfAbsent(published.capture());
        assertThat(published.getValue().valueAmount()).isEqualByComparingTo("67000");
        assertThat(published.getValue().sourceText()).contains("合同金额67000元");
        assertThat(published.getValue().sourceLocator()).endsWith("-8235");
    }

    @Test
    void partialFailedOrUnknownScopeKeepsItsAuditAndCannotPublishSuccessfulScopeFacts() {
        Provenance v2 = new Provenance("provider", "model", "revision",
                "contract-fact-prompt-v2", "contract-fact-candidates-v2");
        when(port.configuredProvenance()).thenReturn(v2);
        coordinator = new DefaultContractFactExtractionTransactionCoordinator(attempts, extractions,
                authority, port, new ContractFactSegmenter(), new ContractFactCandidateValidator(v2));
        for (BatchStatus terminal : List.of(BatchStatus.FAILED, BatchStatus.UNKNOWN)) {
            var prepared = coordinator.prepare(PROJECT, FILE, "partial-" + terminal, ACTOR,
                    new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
            var segment = prepared.request().segments().get(0);
            Candidate candidate = new Candidate("cfc-v2-" + "a".repeat(64), FactType.CONTRACT_AMOUNT, null,
                    null, new BigDecimal("67000"), segment.segmentId(), segment.sourceLocator(),
                    segment.text(), 0, 10, ContextCode.CONTRACT_AMOUNT, new BigDecimal("0.99"));
            Result core = new Result(List.of(candidate), v2, 7, List.of(), BatchStatus.PARTIAL, Scope.CORE,
                    List.of(new ModelCall(1, CallPhase.TRANSPORT, CallOutcome.SUCCEEDED, null,
                            7, null, null, null, null)));
            Result line = new Result(List.of(), v2, 7, List.of(), terminal, Scope.LINE_ITEMS,
                    List.of(new ModelCall(1, CallPhase.TRANSPORT, CallOutcome.FAILED,
                            terminal == BatchStatus.UNKNOWN ? "READ_TIMEOUT" : "MODEL_REFUSED",
                            7, null, null, null, null)));
            var completed = new AtomicReference<ContractFactExtractionResult>();
            org.assertj.core.api.Assertions.assertThatCode(() -> completed.set(coordinator.complete(
                    prepared, List.of(core, line), NOW.plusSeconds(1)))).doesNotThrowAnyException();
            assertThat(completed.get().status()).isEqualTo(terminal == BatchStatus.UNKNOWN ? "OUTCOME_UNKNOWN" : "FAILED");
            assertThat(completed.get().acceptedCount()).isZero();
        }
        verify(extractions, never()).insertIfAbsent(any());
        ArgumentCaptor<List<ContractModelCallAudit>> calls = ArgumentCaptor.forClass(List.class);
        verify(attempts, org.mockito.Mockito.times(2)).failV2(any(), any(), calls.capture(), anyInt(), any(), any());
        assertThat(calls.getAllValues()).allSatisfy(audit -> assertThat(audit).hasSize(2));
    }

    private static ContractFactExtractionAuthority authority() {
        return new ContractFactExtractionAuthority(
                new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "f".repeat(64),
                        PARSE, 2, sha256(TEXT), TEXT, DocumentRole.CONTRACT_BODY, true),
                UUID.fromString("36000000-0000-0000-0000-000000000004"),
                UUID.fromString("36000000-0000-0000-0000-000000000005"));
    }

    @Test
    void denseTwelveAndFortyEightRowTablesAreSplitForOutputCapacityWithoutDroppingTailRows() {
        for (int rowCount : new int[]{12, 48}) {
            StringBuilder source = new StringBuilder("合同编号 TEST-OUTPUT\n合同金额150630元\n行号\t名称\t数量\t单价\t金额\n");
            for (int row = 1; row <= rowCount; row++) {
                source.append("QA2-%03d\t同名维护服务\t2\t100.00\t200.00\n".formatted(row));
            }
            String text = source.toString();
            var original = authority();
            when(authority.resolve(PROJECT, FILE)).thenReturn(new ContractFactExtractionAuthority(
                    new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "f".repeat(64),
                            PARSE, 2, sha256(text), text, DocumentRole.CONTRACT_BODY, true),
                    original.roleDecisionId(), original.identityDecisionId()));
            var prepared = coordinator.prepare(PROJECT, FILE, "output-capacity-" + rowCount, ACTOR,
                    new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);

            for (Scope scope : List.of(Scope.CORE, Scope.LINE_ITEMS)) {
                var scoped = prepared.requests().stream().filter(request -> request.scope() == scope).toList();
                if (scope == Scope.LINE_ITEMS) assertThat(scoped.size()).isLessThanOrEqualTo((rowCount + 3) / 4);
                assertThat(scoped.stream().flatMap(request -> request.segments().stream())
                        .map(segment -> segment.text()).collect(java.util.stream.Collectors.joining()))
                        .isEqualTo(text);
                assertThat(scoped).allSatisfy(request -> {
                    assertThat(request.segments()).hasSizeLessThanOrEqualTo(32);
                    assertThat(request.batchCount()).isEqualTo(scoped.size());
                    if (scope == Scope.LINE_ITEMS) {
                        // Compact rows still expand to four separately grounded durable facts.
                        assertThat(request.segments().stream().filter(segment -> segment.text().contains("\t"))
                                .count()).isLessThanOrEqualTo(6);
                    }
                });
                assertThat(scoped.get(scoped.size() - 1).segments()).anySatisfy(segment ->
                        assertThat(segment.text()).contains("QA2-%03d".formatted(rowCount)));
            }
            var rowIds = prepared.requests().stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                    .flatMap(request -> request.segments().stream()).filter(segment -> segment.lineItemColumns() != null)
                    .map(segment -> segment.rowIdentity()).toList();
            assertThat(rowIds).hasSize(rowCount).doesNotHaveDuplicates();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
