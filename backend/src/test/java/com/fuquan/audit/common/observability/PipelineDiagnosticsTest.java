package com.fuquan.audit.common.observability;

import static org.assertj.core.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

class PipelineDiagnosticsTest {
    @Test void structuredEncoderPreservesCorrelationWithoutDuplicateMdcFields() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PipelineDiagnostics.class);
        Level previous = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs); logger.setLevel(Level.INFO);
        var encoder = new StructuredLogEncoder();
        encoder.setContext(logger.getLoggerContext()); encoder.setFormat("logstash"); encoder.start();
        MDC.put("requestId", "synthetic-structured-request");
        MDC.put("traceId", "synthetic-structured-trace");
        try {
            PipelineDiagnostics.run("PROCUREMENT", "DB_COMPLETE_COMMIT", UUID.randomUUID(), Map.of(), () -> "ok");
            assertThat(logs.list).hasSize(2);
            for (var event : logs.list) {
                String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
                var record = new ObjectMapper().readTree(json);
                assertThat(record.path("requestId").asText()).isEqualTo("synthetic-structured-request");
                assertThat(record.path("traceId").asText()).isEqualTo("synthetic-structured-trace");
                assertThat(record.path("event").asText()).startsWith("PIPELINE_STAGE_");
                assertThat(record.path("message").asText()).contains("requestId=synthetic-structured-request");
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> pair.key.equals("requestId"));
            }
        } finally {
            MDC.remove("requestId"); MDC.remove("traceId"); encoder.stop();
            logger.detachAppender(logs); logger.setLevel(previous); logs.stop();
        }
    }
    @Test void recordsDatabaseCodeAndCorrelationWithoutSqlMessageOrSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(PipelineDiagnostics.class);
        Level previous = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs); logger.setLevel(Level.INFO);
        UUID material = UUID.randomUUID();
        MDC.put("requestId", "synthetic-safe-request");
        try {
            var failure = new RuntimeException("PASSWORD_SENTINEL", new SQLException("BUSINESS_SQL_SENTINEL", "22001", -6004));
            assertThatThrownBy(() -> PipelineDiagnostics.run("PROCUREMENT", "DB_COMPLETE_COMMIT", material,
                    Map.of("inputChars", 400, "prompt", "PROMPT_SENTINEL"), () -> { throw failure; })).isSameAs(failure);
            String text = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a,b) -> a+"\n"+b);
            assertThat(logs.list).hasSize(2);
            assertThat(text).contains("requestId=synthetic-safe-request", material.toString(), "reason=DATABASE_ERROR", "sqlState=22001", "sqlErrorCode=-6004");
            assertThat(text).doesNotContain("PASSWORD_SENTINEL", "BUSINESS_SQL_SENTINEL", "PROMPT_SENTINEL", "outcome=UNKNOWN");
            assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
        } finally { MDC.remove("requestId"); logger.detachAppender(logs); logger.setLevel(previous); logs.stop(); }
    }
    @Test void downstreamCodeIsAllowlistedAndRawBodyIsNeverUsed() {
        var failure = HttpClientErrorException.create(HttpStatus.PAYLOAD_TOO_LARGE, "SECRET_STATUS_SENTINEL", HttpHeaders.EMPTY,
                "{\"code\":\"MODEL_INPUT_TOO_LARGE\",\"message\":\"KEY_SENTINEL\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(PipelineDiagnostics.failure(failure)).containsEntry("reason", "MODEL_INPUT_TOO_LARGE").containsEntry("httpStatus", 413);
        var untrusted = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "SECRET_STATUS_SENTINEL", HttpHeaders.EMPTY,
                "{\"code\":\"KEY_SENTINEL\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(PipelineDiagnostics.failure(untrusted)).containsEntry("reason", "DOWNSTREAM_HTTP_ERROR");
        assertThat(PipelineDiagnostics.failure(untrusted).toString()).doesNotContain("KEY_SENTINEL", "SECRET_STATUS_SENTINEL");
    }
    @Test void distinguishesUnacknowledgedModelCallsFromGenericInternalErrors() {
        var unknown = new com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown(
                "UNACKNOWLEDGED_CALL", new IllegalStateException("provider body secret"));
        assertThat(PipelineDiagnostics.failure(unknown))
                .containsEntry("reason", "UNACKNOWLEDGED_CALL")
                .doesNotContainValue("provider body secret");
        var timeout = new com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated(
                "MATERIAL_CALL_TIMEOUT");
        assertThat(PipelineDiagnostics.failure(timeout)).containsEntry("reason", "MATERIAL_CALL_TIMEOUT");
    }
    @Test void diagnosticResourceIdsNeverCreateABusinessFailure() {
        assertThat(PipelineDiagnostics.resourceId("not-a-uuid")).isNull();
        assertThat(PipelineDiagnostics.resourceId(null)).isNull();
    }
}
