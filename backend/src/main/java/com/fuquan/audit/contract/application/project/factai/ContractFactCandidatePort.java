package com.fuquan.audit.contract.application.project.factai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed boundary for non-authoritative fact candidates over one immutable parse. */
public interface ContractFactCandidatePort {
    Provenance configuredProvenance();

    Result extract(Request request);

    enum FactType {
        CONTRACT_NO, CONTRACT_NAME, PARTY_A, PARTY_B, PARTY_B_CREDIT_CODE,
        CONTRACT_AMOUNT, AMOUNT_IN_WORDS, LINE_ITEM_NAME, LINE_ITEM_QUANTITY,
        LINE_ITEM_UNIT_PRICE, LINE_ITEM_DECLARED_AMOUNT,
        /** Internal native-source fact; never an allowed provider fact or outbound request field. */
        LINE_ITEM_KEY, AWARD_AMOUNT,
        AWARD_SUPPLIER, AWARD_SUPPLIER_CREDIT_CODE
    }

    enum ContextCode {
        CONTRACT_HEADER, CONTRACT_PARTIES, CONTRACT_AMOUNT, CONTRACT_LINE_ITEM,
        AWARD_FINAL_RESULT
    }

    enum Scope { CORE, LINE_ITEMS, AWARD }
    enum BatchStatus { EMPTY, PARTIAL, COMPLETE, FAILED, UNKNOWN }
    enum RejectionStage { TYPED_SCHEMA, SCOPE, EVIDENCE, IDENTITY }
    enum CallPhase { TRANSPORT, REPAIR }
    enum CallOutcome { SUCCEEDED, FAILED }

    record Provenance(String provider, String modelName, String modelRevision,
            String promptVersion, String schemaVersion) {
        public Provenance {
            requireText(provider, "provider");
            requireText(modelName, "modelName");
            requireText(modelRevision, "modelRevision");
            requireText(promptVersion, "promptVersion");
            requireText(schemaVersion, "schemaVersion");
        }
    }

    record Request(UUID projectId, UUID fileId, String fileSha256,
            UUID parseResultId, int parseVersion, String canonicalTextSha256,
            List<Segment> segments, Scope scope, int batchOrdinal, int batchCount,
            long remainingBudgetMs) {
        public Request {
            Objects.requireNonNull(projectId);
            Objects.requireNonNull(fileId);
            Objects.requireNonNull(parseResultId);
            segments = List.copyOf(Objects.requireNonNull(segments));
            Objects.requireNonNull(scope);
            if (batchOrdinal < 1 || batchOrdinal > batchCount || batchCount > ContractFactBatchPlanner.MAX_BATCHES || remainingBudgetMs <= 0) {
                throw new IllegalArgumentException("invalid fact batch identity/budget");
            }
        }

        public Request(UUID projectId, UUID fileId, String fileSha256,
                UUID parseResultId, int parseVersion, String canonicalTextSha256,
                List<Segment> segments, Scope scope) {
            this(projectId, fileId, fileSha256, parseResultId, parseVersion,
                    canonicalTextSha256, segments, scope, 1, 1, 900000);
        }

        public Request withRemainingBudget(long budgetMs) {
            return new Request(projectId, fileId, fileSha256, parseResultId, parseVersion,
                    canonicalTextSha256, segments, scope, batchOrdinal, batchCount, budgetMs);
        }

        public Request(UUID projectId, UUID fileId, String fileSha256,
                UUID parseResultId, int parseVersion, String canonicalTextSha256,
                List<Segment> segments) {
            this(projectId, fileId, fileSha256, parseResultId, parseVersion,
                    canonicalTextSha256, segments, Scope.CORE);
        }
    }

    record Segment(String segmentId, String sourceLocator, String textSha256,
            int startOffset, int endOffset, String text, String rowIdentity,
            List<String> lineItemColumns) {
        public Segment {
            if (lineItemColumns != null) {
                lineItemColumns = List.copyOf(lineItemColumns);
                var columns = new java.util.HashSet<>(lineItemColumns);
                if (rowIdentity == null || columns.size() != lineItemColumns.size()
                        || !columns.containsAll(List.of("name", "quantity", "unitPrice", "declaredAmount"))
                        || !List.of("name", "quantity", "unitPrice", "declaredAmount", "rowId").containsAll(columns)) {
                    throw new IllegalArgumentException("invalid server-owned line columns");
                }
            }
        }
        public Segment(String segmentId, String sourceLocator, String textSha256,
                int startOffset, int endOffset, String text, String rowIdentity) {
            this(segmentId, sourceLocator, textSha256, startOffset, endOffset, text, rowIdentity, null);
        }
        public Segment(String segmentId, String sourceLocator, String textSha256,
                int startOffset, int endOffset, String text) {
            this(segmentId, sourceLocator, textSha256, startOffset, endOffset, text, null);
        }
    }

    record Candidate(String candidateKey, FactType factType, String groupKey,
            String valueText, BigDecimal valueDecimal, String segmentId,
            String sourceLocator, String excerpt, int excerptStart, int excerptEnd,
            ContextCode contextCode, BigDecimal confidence) { }

    record Rejection(int providerOrdinal, RejectionStage stage, String reasonCode,
            String safeShapeHash) {
        public Rejection {
            if (providerOrdinal <= 0) throw new IllegalArgumentException("providerOrdinal must be positive");
            Objects.requireNonNull(stage);
            requireText(reasonCode, "reasonCode");
            if (safeShapeHash == null || !safeShapeHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("safeShapeHash must be SHA-256");
            }
        }
    }

    record ModelCall(int ordinal, CallPhase phase, CallOutcome outcome, String safeReason,
            long durationMs, String finishReason, Integer promptTokens,
            Integer completionTokens, Integer totalTokens) {
        public ModelCall {
            if (ordinal <= 0 || durationMs < 0) throw new IllegalArgumentException("invalid model call counters");
            Objects.requireNonNull(phase); Objects.requireNonNull(outcome);
            for (Integer value : new Integer[]{promptTokens, completionTokens, totalTokens}) {
                if (value != null && value < 0) {
                    throw new IllegalArgumentException("token counts must be non-negative");
                }
            }
        }
    }

    record Result(List<Candidate> candidates, Provenance provenance, long durationMs,
            List<Rejection> rejections, BatchStatus batchStatus, Scope scope,
            List<ModelCall> modelCalls) {
        public Result {
            candidates = List.copyOf(Objects.requireNonNull(candidates));
            Objects.requireNonNull(provenance);
            if (durationMs < 0) throw new IllegalArgumentException("durationMs must be non-negative");
            rejections = List.copyOf(Objects.requireNonNull(rejections));
            Objects.requireNonNull(batchStatus); Objects.requireNonNull(scope);
            modelCalls = List.copyOf(Objects.requireNonNull(modelCalls));
        }

        public Result(List<Candidate> candidates, Provenance provenance, long durationMs) {
            this(candidates, provenance, durationMs, List.of(),
                    candidates.isEmpty() ? BatchStatus.EMPTY : BatchStatus.PARTIAL,
                    Scope.CORE, List.of());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
