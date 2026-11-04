package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.LongSupplier;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.fuquan.audit.common.web.RequestIdFilter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Executes the provider call outside the durable START/COMPLETE transactions. */
@Service
public final class ContractFactExtractionService {
    public static final String RESULT_RESOURCE = "CONTRACT_FACT_EXTRACTION_ATTEMPT";
    private static final Logger LOGGER = LoggerFactory.getLogger(ContractFactExtractionService.class);
    private final ContractFactCandidatePort candidates;
    private final ContractFactExtractionTransactionCoordinator transactions;
    private final Clock clock;
    private final long totalBudgetMs;
    private final LongSupplier nanoTime;

    public ContractFactExtractionService(ContractFactCandidatePort candidates,
            ContractFactExtractionTransactionCoordinator transactions, Clock clock) {
        this(candidates, transactions, clock, 900000, System::nanoTime);
    }

    @Autowired
    public ContractFactExtractionService(ContractFactCandidatePort candidates,
            ContractFactExtractionTransactionCoordinator transactions, Clock clock,
            @Value("${AUDIT_MODEL_TOTAL_BUDGET_SECONDS:900}") long totalBudgetSeconds) {
        this(candidates, transactions, clock, Math.multiplyExact(totalBudgetSeconds, 1000), System::nanoTime);
    }

    ContractFactExtractionService(ContractFactCandidatePort candidates,
            ContractFactExtractionTransactionCoordinator transactions, Clock clock,
            long totalBudgetMs, LongSupplier nanoTime) {
        this.candidates = Objects.requireNonNull(candidates);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (totalBudgetMs <= 0) throw new IllegalArgumentException("fact model total budget must be positive");
        this.totalBudgetMs = totalBudgetMs;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public ContractFactExtractionResult extract(UUID projectId, UUID fileId, String operationKey,
            CurrentUser actor, ProjectRequestMetadata metadata) {
        Instant occurredAt = clock.instant();
        var prepared = transactions.prepare(projectId, fileId, operationKey, actor, metadata, occurredAt);
        if (prepared.replayed()) {
            logTerminal("REPLAYED", "NONE", projectId, fileId, prepared.attemptId(), 0,
                    prepared.requests(), java.util.List.of());
            return prepared.currentResult();
        }
        logPlan(projectId, fileId, prepared, totalBudgetMs);
        long started = nanoTime.getAsLong();
        var results = new ArrayList<ContractFactCandidatePort.Result>();
        try {
            for (var request : prepared.requests()) {
                long remaining = totalBudgetMs - Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000);
                if (remaining <= 0) throw new ContractFactCandidateException(
                        ContractFactCandidateException.Reason.TOTAL_BUDGET_EXCEEDED, null);
                var result = candidates.extract(request.withRemainingBudget(remaining));
                results.add(result);
                if (result.batchStatus() == ContractFactCandidatePort.BatchStatus.FAILED
                        || result.batchStatus() == ContractFactCandidatePort.BatchStatus.UNKNOWN) {
                    boolean unknown = result.batchStatus() == ContractFactCandidatePort.BatchStatus.UNKNOWN;
                    String reason = unknown ? "MODEL_OUTCOME_UNKNOWN" : "MODEL_BATCH_FAILED";
                    transactions.fail(prepared, java.util.List.copyOf(results), reason, clock.instant());
                    String status = unknown ? "OUTCOME_UNKNOWN" : "FAILED";
                    logTerminal(status, reason, projectId, fileId, prepared.attemptId(), elapsedMillis(started),
                            prepared.requests(), results);
                    return new ContractFactExtractionResult(prepared.attemptId(), false, status, 0,
                            results.stream().mapToInt(value -> value.rejections().size()).sum());
                }
            }
            if ((nanoTime.getAsLong() - started) / 1_000_000 >= totalBudgetMs) {
                throw new ContractFactCandidateException(ContractFactCandidateException.Reason.TOTAL_BUDGET_EXCEEDED, null);
            }
            ContractFactExtractionResult result = results.size() == 1
                    ? transactions.complete(prepared, results.get(0), clock.instant())
                    : transactions.complete(prepared, results, clock.instant());
            logTerminal(result.status(), "NONE", projectId, fileId, prepared.attemptId(),
                    elapsedMillis(started), prepared.requests(), results);
            return result;
        } catch (ContractFactCandidateException failure) {
            closeFailure(prepared, results, failure.getReason().name());
            logTerminal(ContractFactExtractionAttempt.failureStatus(failure.getReason().name()).name(),
                    failure.getReason().name(), projectId, fileId,
                    prepared.attemptId(), elapsedMillis(started), prepared.requests(), results);
            throw failure;
        } catch (RuntimeException failure) {
            ContractFactExtractionDiagnostics.log(LOGGER, failure);
            closeFailure(prepared, results, "UNEXPECTED_FAILURE");
            logTerminal("OUTCOME_UNCERTAIN", "UNEXPECTED_FAILURE", projectId, fileId,
                    prepared.attemptId(), elapsedMillis(started), prepared.requests(), results);
            throw failure;
        }
    }

    private void closeFailure(ContractFactExtractionTransactionCoordinator.Prepared prepared,
            java.util.List<ContractFactCandidatePort.Result> completedResults, String reason) {
        if (completedResults.isEmpty()) {
            transactions.fail(prepared, reason, clock.instant());
        } else {
            transactions.fail(prepared, java.util.List.copyOf(completedResults), reason, clock.instant());
        }
    }

    private static void logPlan(UUID projectId, UUID fileId,
            ContractFactExtractionTransactionCoordinator.Prepared prepared, long totalBudgetMs) {
        int segmentCount = prepared.requests().stream().mapToInt(request -> request.segments().size()).sum();
        int inputCodePoints = prepared.requests().stream().flatMap(request -> request.segments().stream())
                .mapToInt(segment -> segment.text().codePointCount(0, segment.text().length())).sum();
        int maxBatchCodePoints = prepared.requests().stream().mapToInt(request -> request.segments().stream()
                .mapToInt(segment -> segment.text().codePointCount(0, segment.text().length())).sum()).max().orElse(0);
        long coreBatches = prepared.requests().stream().filter(request -> request.scope()
                == ContractFactCandidatePort.Scope.CORE).count();
        long lineBatches = prepared.requests().stream().filter(request -> request.scope()
                == ContractFactCandidatePort.Scope.LINE_ITEMS).count();
        long awardBatches = prepared.requests().stream().filter(request -> request.scope()
                == ContractFactCandidatePort.Scope.AWARD).count();
        LOGGER.info("event=FACT_EXTRACTION_PLAN diagnosticSchemaVersion=1 module=CONTRACT layer=APPLICATION "
                        + "operation=extractContractFacts stage=FACT_EXTRACTION_PLAN outcome=PLANNED "
                        + "reasonCode=NONE requestId={} traceId={} projectId={} fileId={} attemptId={} "
                        + "plannedBatchCount={} plannedSegmentCount={} submittedCodePointCount={} "
                        + "maxBatchCodePointCount={} coreBatchCount={} lineItemBatchCount={} awardBatchCount={} "
                        + "maxDocumentCodePoints={} maxBatchSegments={} maxBatchesPerScope={} totalBudgetMs={}",
                MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), projectId, fileId,
                prepared.attemptId(), prepared.requests().size(), segmentCount, inputCodePoints,
                maxBatchCodePoints, coreBatches, lineBatches, awardBatches,
                ContractFactSegmenter.MAX_DOCUMENT_CODE_POINTS, ContractFactSegmenter.MAX_SEGMENTS,
                ContractFactBatchPlanner.MAX_BATCHES, totalBudgetMs);
    }

    private static void logTerminal(String outcome, String reasonCode, UUID projectId,
            UUID fileId, UUID attemptId, long durationMs,
            java.util.List<ContractFactCandidatePort.Request> requests,
            java.util.List<ContractFactCandidatePort.Result> results) {
        int plannedSegments = requests.stream().mapToInt(request -> request.segments().size()).sum();
        int candidateCount = results.stream().mapToInt(result -> result.candidates().size()).sum();
        int rejectedCount = results.stream().mapToInt(result -> result.rejections().size()).sum();
        int modelCallCount = results.stream().mapToInt(result -> result.modelCalls().size()).sum();
        long modelCallDurationMs = results.stream().flatMap(result -> result.modelCalls().stream())
                .mapToLong(ContractFactCandidatePort.ModelCall::durationMs).sum();
        long promptTokens = results.stream().flatMap(result -> result.modelCalls().stream())
                .map(ContractFactCandidatePort.ModelCall::promptTokens).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        long completionTokens = results.stream().flatMap(result -> result.modelCalls().stream())
                .map(ContractFactCandidatePort.ModelCall::completionTokens).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        long totalTokens = results.stream().flatMap(result -> result.modelCalls().stream())
                .map(ContractFactCandidatePort.ModelCall::totalTokens).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        LOGGER.info("event=DM8_COMPLETION diagnosticSchemaVersion=1 module=CONTRACT layer=APPLICATION "
                        + "operation=extractContractFacts stage=DM8_COMPLETION outcome={} reasonCode={} "
                        + "requestId={} traceId={} projectId={} fileId={} attemptId={} "
                        + "idempotencyScope=CONTRACT_FACT_EXTRACTION operationKeyHash=null durationMs={} "
                        + "plannedBatchCount={} completedBatchCount={} plannedSegmentCount={} candidateCount={} "
                        + "evidenceRejectedCount={} modelCallCount={} modelCallDurationMs={} "
                        + "promptTokens={} completionTokens={} totalTokens={}",
                outcome, reasonCode, MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"),
                projectId, fileId, attemptId, durationMs, requests.size(), results.size(), plannedSegments,
                candidateCount, rejectedCount, modelCallCount, modelCallDurationMs,
                promptTokens, completionTokens, totalTokens);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
