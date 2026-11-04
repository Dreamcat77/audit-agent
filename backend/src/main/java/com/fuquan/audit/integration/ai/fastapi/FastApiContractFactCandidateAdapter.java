package com.fuquan.audit.integration.ai.fastapi;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidateException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidateException.Reason;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.factai.ContractFactSegmenter;
import com.fuquan.audit.integration.http.FastApiContractPaths;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile({"local-dev & local-ai", "dev29", "customer"})
public final class FastApiContractFactCandidateAdapter implements ContractFactCandidatePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(FastApiContractFactCandidateAdapter.class);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CANDIDATE_KEY = Pattern.compile("^cfc-v2-[0-9a-f]{64}$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z0-9_]{1,64}$");
    private static final Pattern DECIMAL = Pattern.compile("^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Set<FactType> DECIMAL_FACTS = Set.of(FactType.CONTRACT_AMOUNT,
            FactType.LINE_ITEM_QUANTITY, FactType.LINE_ITEM_UNIT_PRICE,
            FactType.LINE_ITEM_DECLARED_AMOUNT, FactType.AWARD_AMOUNT);
    private static final Set<FactType> LINE_FACTS = Set.of(FactType.LINE_ITEM_NAME,
            FactType.LINE_ITEM_QUANTITY, FactType.LINE_ITEM_UNIT_PRICE,
            FactType.LINE_ITEM_DECLARED_AMOUNT);
    private static final Map<FactType, ContextCode> CONTEXTS = Map.ofEntries(
            Map.entry(FactType.CONTRACT_NO, ContextCode.CONTRACT_HEADER),
            Map.entry(FactType.CONTRACT_NAME, ContextCode.CONTRACT_HEADER),
            Map.entry(FactType.PARTY_A, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.PARTY_B, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.PARTY_B_CREDIT_CODE, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.CONTRACT_AMOUNT, ContextCode.CONTRACT_AMOUNT),
            Map.entry(FactType.AMOUNT_IN_WORDS, ContextCode.CONTRACT_AMOUNT),
            Map.entry(FactType.LINE_ITEM_NAME, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_QUANTITY, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_UNIT_PRICE, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_DECLARED_AMOUNT, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.AWARD_AMOUNT, ContextCode.AWARD_FINAL_RESULT),
            Map.entry(FactType.AWARD_SUPPLIER, ContextCode.AWARD_FINAL_RESULT),
            Map.entry(FactType.AWARD_SUPPLIER_CREDIT_CODE, ContextCode.AWARD_FINAL_RESULT));
    private static final String PROVIDER_PREFIX = "OPENAI_COMPATIBLE_ENDPOINT_SHA256:";
    private static final String REVISION = "NOT_DECLARED_BY_PROVIDER";
    private static final String PROMPT = "contract-fact-prompt-v2.3";
    private static final String SCHEMA = "contract-fact-candidates-v2";

    private final RestClient restClient;
    private final Provenance provenance;

    public FastApiContractFactCandidateAdapter(@Qualifier("aiRestClient") RestClient restClient,
            @Value("${audit.ai.contract-fact.model-name:${AUDIT_MODEL_NAME:configured-model}}")
            String modelName,
            @Value("${AUDIT_MODEL_BASE_URL:}") String modelBaseUrl) {
        this.restClient = restClient;
        this.provenance = new Provenance(providerIdentity(modelBaseUrl), modelName, REVISION, PROMPT, SCHEMA);
    }

    @Override
    public Provenance configuredProvenance() {
        return provenance;
    }

    @Override
    public Result extract(Request request) {
        validateRequest(request);
        long started = System.nanoTime();
        logLifecycle("AI_HTTP_STARTED", request, "REQUEST_STARTED", "NONE", null, 0);
        try {
            RestClient.RequestBodySpec http = restClient.post()
                    .uri(FastApiContractPaths.CONTRACT_FACT_CANDIDATES_V2);
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null && !requestId.isBlank()) {
                http.header(RequestIdFilter.HEADER_NAME, requestId);
            }
            ResponseEntity<Envelope> response = http.contentType(MediaType.APPLICATION_JSON)
                    .body(wire(request)).retrieve().toEntity(Envelope.class);
            Result result;
            try {
                result = map(request, response.getBody());
            } catch (ContractFactCandidateException failure) {
                logLifecycle("AI_HTTP_COMPLETED", request, outcome(failure.getReason()),
                        failure.getReason().name(), response.getStatusCode().value(),
                        elapsedMillis(started));
                throw failure;
            }
            logLifecycle("AI_HTTP_COMPLETED", request, "HTTP_RESPONSE_RECEIVED", "NONE",
                    response.getStatusCode().value(), elapsedMillis(started));
            LOGGER.info("event=FACT_RESULT_VALIDATION diagnosticSchemaVersion=1 module=CONTRACT "
                            + "layer=INTEGRATION operation=extractContractFacts stage=FACT_RESULT_VALIDATION "
                            + "outcome=ACCEPTED reasonCode=NONE requestId={} traceId={} projectId={} fileId={} "
                            + "attemptId=null idempotencyScope=CONTRACT_FACT_CANDIDATES_V2 "
                            + "operationKeyHash=null durationMs={} acceptedCount={} rejectedCount={} scope={}",
                    MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), request.projectId(), request.fileId(),
                    elapsedMillis(started), result.candidates().size(), result.rejections().size(), request.scope());
            return result;
        } catch (ContractFactCandidateException failure) {
            throw failure;
        } catch (ResourceAccessException failure) {
            Reason reason = transportReason(failure);
            logLifecycle("AI_HTTP_COMPLETED", request, outcome(reason), reason.name(), null,
                    elapsedMillis(started));
            throw new ContractFactCandidateException(reason, failure);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            Reason reason = status == 504 ? Reason.TIMEOUT
                    : status >= 500 && !provenNotCalled(failure) ? Reason.OUTCOME_UNKNOWN : Reason.UNAVAILABLE;
            logLifecycle("AI_HTTP_COMPLETED", request, "HTTP_RESPONSE_RECEIVED", reason.name(),
                    failure.getStatusCode().value(), elapsedMillis(started));
            throw new ContractFactCandidateException(reason, failure);
        } catch (RestClientException | IllegalArgumentException failure) {
            logLifecycle("AI_HTTP_COMPLETED", request, "RESULT_INVALID", Reason.INVALID_RESPONSE.name(),
                    null, elapsedMillis(started));
            throw new ContractFactCandidateException(Reason.INVALID_RESPONSE, failure);
        }
    }

    private static void logLifecycle(String event, Request request, String outcome, String reasonCode,
            Integer status, long durationMs) {
        LOGGER.info("event={} diagnosticSchemaVersion=1 module=CONTRACT layer=INTEGRATION "
                        + "operation=extractContractFacts stage=AI_HTTP outcome={} reasonCode={} "
                        + "requestId={} traceId={} projectId={} fileId={} attemptId=null "
                        + "idempotencyScope=CONTRACT_FACT_CANDIDATES_V2 operationKeyHash=null "
                        + "durationMs={} httpStatus={} scope={}",
                event, outcome, reasonCode, MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"),
                request.projectId(), request.fileId(), durationMs, status, request.scope());
    }

    private static String outcome(Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "TIMEOUT";
            case UNAVAILABLE -> "CONNECTION_FAILED";
            case INVALID_RESPONSE -> "RESULT_INVALID";
            case BUSINESS_REFUSED -> "BUSINESS_REFUSED";
            case TOTAL_BUDGET_EXCEEDED -> "OUTCOME_UNCERTAIN";
            case OUTCOME_UNKNOWN -> "OUTCOME_UNCERTAIN";
        };
    }

    private static Reason transportReason(Throwable failure) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = failure; cause != null && seen.size() < 12 && seen.add(cause);
                cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                return Reason.TIMEOUT;
            }
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
                return Reason.UNAVAILABLE;
            }
        }
        return Reason.OUTCOME_UNKNOWN;
    }

    private static boolean provenNotCalled(RestClientResponseException failure) {
        if (failure.getStatusCode().value() != 503 || failure.getResponseBodyAsByteArray().length > 32768) return false;
        try {
            String code = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(failure.getResponseBodyAsByteArray()).path("code").asText();
            return Set.of("CONTRACT_FACT_EXTRACTION_DISABLED", "CONTRACT_FACT_UNAVAILABLE").contains(code);
        } catch (Exception unproven) {
            return false;
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private Result map(Request request, Envelope envelope) {
        if (envelope != null && envelope.code() != null && envelope.code() >= 400 && envelope.code() <= 599) {
            throw new ContractFactCandidateException(Reason.BUSINESS_REFUSED,
                    new IllegalStateException("downstream business refusal"));
        }
        if (envelope == null || envelope.code() == null || envelope.code() != 200
                || envelope.data() == null || envelope.data().candidates() == null
                || envelope.data().rejections() == null || envelope.data().modelCalls() == null
                || envelope.data().scope() == null || envelope.data().batchStatus() == null
                || envelope.data().durationMs() == null || envelope.data().durationMs() < 0) {
            throw invalid();
        }
        Data data = envelope.data();
        Provenance returned;
        try {
            returned = new Provenance(data.modelProvider(), data.modelName(), data.modelRevision(),
                    data.promptVersion(), data.schemaVersion());
        } catch (RuntimeException failure) {
            throw invalid();
        }
        if (!provenance.equals(returned)) throw invalid();
        if (data.scope() != request.scope()) throw invalid();
        if ((data.batchOrdinal() != null && data.batchOrdinal() != request.batchOrdinal())
                || (data.batchCount() != null && data.batchCount() != request.batchCount())
                || (request.batchCount() > 1 && (data.batchOrdinal() == null || data.batchCount() == null))) {
            throw invalid();
        }
        Map<String, Segment> segments = new HashMap<>();
        request.segments().forEach(segment -> segments.put(segment.segmentId(), segment));
        Set<String> candidateKeys = new HashSet<>();
        List<Candidate> candidates = data.candidates().stream().map(value -> {
            if (value == null || value.candidateKey() == null
                    || !CANDIDATE_KEY.matcher(value.candidateKey()).matches()
                    || !candidateKeys.add(value.candidateKey()) || value.factType() == null
                    || value.contextCode() == null || CONTEXTS.get(value.factType()) != value.contextCode()
                    || value.confidence() == null || outsideConfidence(value.confidence())
                    || value.excerptStart() == null || value.excerptEnd() == null
                    || value.excerptStart() < 0 || value.excerptEnd() <= value.excerptStart()) {
                throw invalid();
            }
            boolean decimalFact = DECIMAL_FACTS.contains(value.factType());
            if (decimalFact != (value.valueDecimal() != null)
                    || decimalFact == (value.valueText() != null)
                    || (decimalFact && !DECIMAL.matcher(value.valueDecimal()).matches())
                    || LINE_FACTS.contains(value.factType()) != (value.groupKey() != null)) {
                throw invalid();
            }
            Segment segment = segments.get(value.segmentId());
            if (segment != null && LINE_FACTS.contains(value.factType()) && segment.rowIdentity() != null
                    && !ContractFactSegmenter.lineGroup(segment.rowIdentity()).equals(value.groupKey())) throw invalid();
            if (segment == null || !segment.sourceLocator().equals(value.sourceLocator())
                    || value.excerpt() == null || value.excerpt().isBlank()
                    || value.excerptEnd() > codePointLength(segment.text())
                    || !codePointSlice(segment.text(), value.excerptStart(), value.excerptEnd())
                    .equals(value.excerpt())) {
                throw invalid();
            }
            BigDecimal decimalValue = decimalFact ? new BigDecimal(value.valueDecimal()) : null;
            if (LINE_FACTS.contains(value.factType()) && segment.lineItemColumns() != null
                    && !mappedCellMatches(segment, value, decimalValue)) throw invalid();
            return new Candidate(value.candidateKey(), value.factType(), value.groupKey(),
                    value.valueText(), decimalValue, value.segmentId(), value.sourceLocator(),
                    value.excerpt(), value.excerptStart(), value.excerptEnd(),
                    value.contextCode(), value.confidence());
        }).toList();
        Set<Integer> rejectionOrdinals = new HashSet<>();
        List<Rejection> rejections = data.rejections().stream().map(value -> {
            if (value == null || value.providerOrdinal() == null || value.providerOrdinal() <= 0
                    || value.providerOrdinal() > 100
                    || !rejectionOrdinals.add(value.providerOrdinal()) || value.stage() == null
                    || value.reasonCode() == null || !SAFE_CODE.matcher(value.reasonCode()).matches()
                    || !safeSha(value.safeShapeHash())) throw invalid();
            return new Rejection(value.providerOrdinal(), value.stage(), value.reasonCode(),
                    value.safeShapeHash());
        }).toList();
        Set<Integer> callOrdinals = new HashSet<>();
        List<ModelCall> modelCalls = data.modelCalls().stream().map(value -> {
            if (value == null || value.ordinal() == null || value.ordinal() <= 0 || value.ordinal() > 2
                    || !callOrdinals.add(value.ordinal()) || value.phase() == null
                    || value.outcome() == null || value.durationMs() == null || value.durationMs() < 0
                    || (value.safeReason() != null && !SAFE_CODE.matcher(value.safeReason()).matches())
                    || negative(value.promptTokens()) || negative(value.completionTokens())
                    || negative(value.totalTokens())) throw invalid();
            return new ModelCall(value.ordinal(), value.phase(), value.outcome(), value.safeReason(),
                    value.durationMs(), value.finishReason(), value.promptTokens(),
                    value.completionTokens(), value.totalTokens());
        }).toList();
        return new Result(candidates, returned, data.durationMs(), rejections,
                data.batchStatus(), data.scope(), modelCalls);
    }

    private static void validateRequest(Request request) {
        if (request == null || request.projectId() == null || request.fileId() == null
                || request.parseResultId() == null || request.parseVersion() <= 0
                || !safeSha(request.fileSha256()) || !safeSha(request.canonicalTextSha256())
                || request.segments() == null || request.segments().isEmpty()
                || request.segments().size() > 32) throw invalid();
        Set<String> ids = new HashSet<>();
        for (Segment segment : request.segments()) {
            if (segment == null || segment.segmentId() == null || segment.segmentId().isBlank()
                    || !ids.add(segment.segmentId()) || segment.startOffset() < 0
                    || segment.endOffset() <= segment.startOffset() || segment.text() == null
                    || segment.text().isEmpty() || codePointLength(segment.text()) > 240
                    || (segment.rowIdentity() != null && !segment.rowIdentity().matches("row:[0-9a-f]{64}"))
                    || segment.endOffset() - segment.startOffset() != codePointLength(segment.text())
                    || !safeSha(segment.textSha256()) || !sha256(segment.text()).equals(segment.textSha256())) {
                throw invalid();
            }
            String locator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                    request.parseResultId(), segment.startOffset(), segment.endOffset());
            if (!locator.equals(segment.sourceLocator())) throw invalid();
        }
    }

    private static WireRequest wire(Request request) {
        return new WireRequest(request.projectId(), request.fileId(), request.fileSha256(),
                request.parseResultId(), request.parseVersion(), request.canonicalTextSha256(),
                request.scope(), request.batchOrdinal(), request.batchCount(), request.remainingBudgetMs(),
                request.segments().stream().map(value -> new WireEvidence(value.segmentId(),
                        value.sourceLocator(), value.textSha256(), value.startOffset(),
                        value.endOffset(), value.text(), value.rowIdentity(), value.lineItemColumns())).toList());
    }

    private static boolean negative(Integer value) { return value != null && value < 0; }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String codePointSlice(String value, int start, int end) {
        int startIndex = value.offsetByCodePoints(0, start);
        int endIndex = value.offsetByCodePoints(0, end);
        return value.substring(startIndex, endIndex);
    }

    private static boolean outsideConfidence(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0;
    }

    private static boolean safeSha(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String providerIdentity(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("model provider endpoint identity is required");
        }
        java.net.URI uri = java.net.URI.create(baseUrl.strip()).normalize();
        if (uri.getScheme() == null || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("model provider endpoint identity is invalid");
        }
        String authority = uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        String normalized = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + authority + path;
        return PROVIDER_PREFIX + sha256(normalized);
    }

    private static boolean mappedCellMatches(Segment segment, CandidateData candidate, BigDecimal decimal) {
        String column = switch (candidate.factType()) {
            case LINE_ITEM_NAME -> "name";
            case LINE_ITEM_QUANTITY -> "quantity";
            case LINE_ITEM_UNIT_PRICE -> "unitPrice";
            case LINE_ITEM_DECLARED_AMOUNT -> "declaredAmount";
            default -> throw new IllegalArgumentException("not a line fact");
        };
        String[] cells = segment.text().replaceAll("[\\r\\n]+$", "").split("\\t", -1);
        if (cells.length != segment.lineItemColumns().size()) return false;
        String cell = cells[segment.lineItemColumns().indexOf(column)];
        if (cell.indexOf('\r') >= 0 || cell.indexOf('\n') >= 0) return false;
        if (decimal == null) return cell.equals(candidate.valueText());
        String numeric = cell.strip();
        return numeric.matches("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
                && decimal.compareTo(new BigDecimal(numeric.replace(",", ""))) == 0;
    }

    private static ContractFactCandidateException invalid() {
        return new ContractFactCandidateException(Reason.INVALID_RESPONSE,
                new IllegalStateException("invalid downstream contract fact response"));
    }

    private record WireRequest(java.util.UUID projectId, java.util.UUID fileId, String fileSha256,
            java.util.UUID parseResultId, int parseVersion, String canonicalTextSha256,
            Scope scope, int batchOrdinal, int batchCount, long remainingBudgetMs,
            List<WireEvidence> evidenceCatalog) { }
    private record WireEvidence(String evidenceId, String sourceLocator, String textSha256,
            int startOffset, int endOffset, String text, String rowIdentity, List<String> lineItemColumns) { }
    private record Envelope(Integer code, String message, Data data) { }
    private record Data(List<CandidateData> candidates, List<RejectionData> rejections,
            Scope scope, BatchStatus batchStatus, Integer batchOrdinal, Integer batchCount, List<ModelCallData> modelCalls,
            String schemaVersion, String promptVersion, String modelProvider,
            String modelName, String modelRevision, Long durationMs) { }
    private record CandidateData(String candidateKey, FactType factType, String groupKey,
            String valueText, String valueDecimal, String segmentId, String sourceLocator,
            String excerpt, Integer excerptStart, Integer excerptEnd,
            ContextCode contextCode, BigDecimal confidence) { }
    private record RejectionData(Integer providerOrdinal, RejectionStage stage,
            String reasonCode, String safeShapeHash) { }
    private record ModelCallData(Integer ordinal, CallPhase phase, CallOutcome outcome,
            String safeReason, Long durationMs, String finishReason, Integer promptTokens,
            Integer completionTokens, Integer totalTokens) { }
}
