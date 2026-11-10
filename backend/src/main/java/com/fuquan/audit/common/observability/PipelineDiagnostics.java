package com.fuquan.audit.common.observability;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.common.exception.BusinessException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Content-free INFO diagnostics. Never replaces durable business auditing. */
public final class PipelineDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineDiagnostics.class);
    private static final Set<String> COUNTS = Set.of("inputBytes", "inputChars", "outputChars", "segmentCount",
            "pageCount", "itemCount", "rowCount", "factCount", "fileCount", "httpStatus", "byteLimit",
            "textLimit", "connectTimeoutMs", "readTimeoutMs", "responseBytes", "sqlErrorCode", "errorDepth", "stackLine",
            "nativeTextChars", "ocrTextChars", "emptyOcrPages", "emptyChunkCount", "evidenceRejectedCount",
            "callCount", "findingCount", "ruleCount", "durationMs", "pageNumber", "workerCount", "queueWaitMs", "candidateCount", "acceptedCount");
    private static final Set<String> TOKENS = Set.of("outcome", "parseMethod", "reason", "errorClass", "sqlState",
            "stackClass", "stackMethod", "rootCauseClass", "reasonCode", "aiStatus", "businessType",
            "parseEngine", "classification");
    private static final Set<String> DOWNSTREAM_CODES = Set.of("MODEL_INPUT_TOO_LARGE", "MODEL_NOT_CONFIGURED",
            "MODEL_GATEWAY_ERROR", "MODEL_INVALID_RESULT", "MODEL_TOTAL_BUDGET_EXHAUSTED", "REQUEST_VALIDATION_FAILED",
            "MATERIAL_AI_BUSY", "MATERIAL_AI_INPUT_LIMIT", "MATERIAL_AI_DUPLICATE_SOURCE", "MATERIAL_AI_TIME_LIMIT",
            "MATERIAL_AI_TRUNCATED", "MATERIAL_AI_INVALID_RESULT", "MATERIAL_AI_UNGROUNDED", "MATERIAL_AI_RESULT_LIMIT",
            "MATERIAL_AI_UNAVAILABLE", "MATERIAL_AI_FAILED", "OCR_RUNTIME_UNAVAILABLE", "OCR_INVALID_RESULT",
            "OCR_EXECUTION_FAILED", "OCR_UNSUPPORTED_MEDIA_TYPE", "UPLOAD_TOO_LARGE", "EMPTY_UPLOAD");
    private PipelineDiagnostics() {}
    public static UUID resourceId(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException | NullPointerException ignored) { return null; }
    }

    public static Span start(String operation, String stage, Object resourceId, Map<String, ?> metadata) {
        return new Span(operation, stage, resourceId, metadata);
    }

    public static <T> T run(String operation, String stage, Object resourceId, Map<String, ?> metadata, Supplier<T> action) {
        try (var span = start(operation, stage, resourceId, metadata)) {
            try {
                T result = action.get();
                span.success(Map.of());
                return result;
            } catch (RuntimeException | Error error) {
                span.fail(error);
                throw error;
            }
        }
    }

    public static Map<String, Object> failure(Throwable error) {
        var result = new LinkedHashMap<String, Object>();
        result.put("reason", "INTERNAL_ERROR");
        result.put("errorClass", error.getClass().getSimpleName());
        var frame = SafeExceptionDiagnostics.safeTopStackFrame(error);
        result.put("stackClass", frame.className()); result.put("stackMethod", frame.methodName()); result.put("stackLine", frame.lineNumber());
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.size() < 12 && seen.add(cause); cause = cause.getCause()) {
            result.put("rootCauseClass", cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql) {
                result.put("reason", "DATABASE_ERROR");
                result.put("sqlErrorCode", sql.getErrorCode());
                result.put("sqlState", sql.getSQLState() == null ? "UNKNOWN" : sql.getSQLState());
            } else if (cause instanceof com.fuquan.audit.common.material.application.port.DocumentTextPort.ReadFailure read) {
                result.put("reason",read.code());
            } else if (cause instanceof BusinessException business) {
                result.put("reason", business.errorCode().name());
            } else if (cause instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown unknown) {
                result.put("reason", unknown.reasonCode());
            } else if (cause instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated terminated) {
                result.put("reason", terminated.reasonCode());
            } else if (cause instanceof RestClientResponseException http) {
                result.put("reason", "DOWNSTREAM_HTTP_ERROR");
                result.put("httpStatus", http.getStatusCode().value());
                byte[] body = http.getResponseBodyAsByteArray();
                if (body.length <= 32768) try {
                    String code = new ObjectMapper().readTree(body).path("code").asText();
                    if (DOWNSTREAM_CODES.contains(code)) result.put("reason", code);
                } catch (Exception ignored) { /* raw downstream bodies never enter logs */ }
            } else if (cause instanceof SocketTimeoutException) result.put("reason", "DOWNSTREAM_TIMEOUT");
            else if (cause instanceof ConnectException) result.put("reason", "DOWNSTREAM_CONNECT_ERROR");
        }
        result.put("errorDepth", seen.size());
        return result;
    }

    private static String token(Object value) {
        String s = String.valueOf(value);
        return s.matches("[A-Za-z0-9._-]{1,128}") ? s : "OMITTED";
    }

    public static final class Span implements AutoCloseable {
        private final String operation, stage, resourceId, stageId = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private boolean ended;
        private Span(String operation, String stage, Object resourceId, Map<String, ?> metadata) {
            this.operation = token(operation); this.stage = token(stage);
            this.resourceId = resourceId instanceof UUID ? resourceId.toString() : "unavailable";
            emit("PIPELINE_STAGE_START", "START", metadata);
        }
        public void success(Map<String, ?> metadata) { end("SUCCESS", metadata); }
        public void outcome(String outcome, Map<String, ?> metadata) { end(token(outcome), metadata); }
        public void fail(Throwable error) { end("FAILED", failure(error)); }
        private void end(String outcome, Map<String, ?> metadata) {
            if (!ended) { ended = true; emit("PIPELINE_STAGE_END", outcome, metadata); }
        }
        private void emit(String event, String outcome, Map<String, ?> metadata) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("requestId", token(Optional.ofNullable(MDC.get(RequestIdFilter.MDC_KEY)).orElse("unavailable")));
            fields.put("event", event); fields.put("operation", operation); fields.put("stage", stage);
            fields.put("stageId", stageId); fields.put("resourceId", resourceId);
            fields.put("outcome", outcome); fields.put("durationMs", Math.max(0L, (System.nanoTime()-started)/1_000_000));
            metadata.forEach((key, value) -> {
                if (COUNTS.contains(key) && value instanceof Number && !(value instanceof Float) && !(value instanceof Double))
                    fields.put(key, value);
                else if (TOKENS.contains(key)) fields.put(key, token(value));
            });
            var builder = LOG.atInfo();
            // Boot's Logstash encoder writes MDC before fluent fields and rejects duplicate names.
            // Keep correlation in the plain message, but use the existing MDC JSON field once.
            fields.forEach((key, value) -> {
                if (MDC.get(key) == null) builder.addKeyValue(key, value);
            });
            builder.log(String.join(" ", fields.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()));
        }
        @Override public void close() { end("UNKNOWN", Map.of("reason", "STAGE_NOT_CONFIRMED")); }
    }
}
