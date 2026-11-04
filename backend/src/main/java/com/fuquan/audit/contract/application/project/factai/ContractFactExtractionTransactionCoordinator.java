package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short-transaction boundary around the remote contract-fact call. */
public interface ContractFactExtractionTransactionCoordinator {
    Prepared prepare(UUID projectId, UUID fileId, String operationKey,
            CurrentUser actor, ProjectRequestMetadata metadata, Instant occurredAt);

    ContractFactExtractionResult complete(Prepared prepared, List<Result> providerResults, Instant occurredAt);

    default ContractFactExtractionResult complete(Prepared prepared, Result providerResult, Instant occurredAt) {
        if (prepared.requests().stream().filter(request -> request.scope() == providerResult.scope()).count() != 1) {
            throw new IllegalArgumentException("single-result completion cannot cover multiple batches");
        }
        return complete(prepared, prepared.requests().stream().map(request ->
                request.scope() == providerResult.scope() ? providerResult :
                new Result(List.of(), providerResult.provenance(), 0, List.of(),
                        ContractFactCandidatePort.BatchStatus.EMPTY, request.scope(), List.of())).toList(), occurredAt);
    }

    void fail(Prepared prepared, String reason, Instant occurredAt);

    /** Preserve only proved provider audit from the completed request prefix; publish no business facts. */
    void fail(Prepared prepared, List<Result> completedResults, String reason, Instant occurredAt);

    record Prepared(UUID attemptId, List<Request> requests, boolean replayed,
            ContractFactExtractionResult currentResult,
            List<ContractFactSourceTablePlanner.SourceBatch> sourceBatches) {
        public Prepared {
            Objects.requireNonNull(attemptId, "attemptId must not be null");
            requests = List.copyOf(Objects.requireNonNull(requests, "requests must not be null"));
            if (requests.isEmpty()) throw new IllegalArgumentException("at least one request is required");
            Objects.requireNonNull(currentResult, "currentResult must not be null");
            sourceBatches = List.copyOf(Objects.requireNonNull(sourceBatches));
        }
        public Prepared(UUID attemptId, List<Request> requests, boolean replayed,
                ContractFactExtractionResult currentResult) {
            this(attemptId, requests, replayed, currentResult, List.of());
        }
        public Prepared(UUID attemptId, Request request, boolean replayed,
                ContractFactExtractionResult currentResult) {
            this(attemptId, List.of(request), replayed, currentResult);
        }
        public Request request() { return requests.get(0); }
    }
}
