package com.fuquan.audit.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = {
        "audit.observability.flow-debug-enabled=true",
        "logging.level.com.fuquan.audit.common.observability.FlowDebugLogger=DEBUG"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TraceContextIntegrationTest.TraceProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
class TraceContextIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void traceSpanAndRequestIdAreSimultaneouslyAvailableInMdcAndTechnicalLog(CapturedOutput output)
            throws Exception {
        String requestId = "trace-request-20260817";

        MvcResult result = mockMvc.perform(get("/test-only/trace-context")
                        .header(RequestIdFilter.HEADER_NAME, requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(requestId))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.spanId").isNotEmpty())
                .andExpect(jsonPath("$.data.tracerTraceId").isNotEmpty())
                .andExpect(jsonPath("$.data.tracerSpanId").isNotEmpty())
                .andReturn();

        String traceId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.traceId");
        String spanId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.spanId");
        String tracerTraceId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.tracerTraceId");
        String tracerSpanId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.tracerSpanId");

        assertThat(traceId)
                .matches("[0-9a-f]{32}")
                .isEqualTo(tracerTraceId);
        assertThat(spanId)
                .matches("[0-9a-f]{16}")
                .isEqualTo(tracerSpanId);

        String rendered = output.toString();
        assertThat(output).contains("HTTP_IN")
                .contains("HTTP_OUT");
        assertThat(hasCorrelatedEvent(rendered, "HTTP_IN", traceId, requestId)).isTrue();
        assertThat(hasCorrelatedEvent(rendered, "HTTP_OUT", traceId, requestId)).isTrue();
    }

    @Test
    void healthKeepsAccessLogButSkipsFlowDebugEntry(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());

        assertThat(output).contains("HTTP_OUT").doesNotContain("HTTP_IN");
    }

    private boolean hasCorrelatedEvent(String output, String event, String traceId, String requestId) {
        String plain = "(?m)^.*traceId:"
                + Pattern.quote(traceId)
                + " spanId:[0-9a-f]{16} requestId:"
                + Pattern.quote(requestId)
                + ".*"
                + event
                + ".*$";
        String structured = "(?m)^.*\\\"message\\\":\\\""
                + event
                + ".*\\\"traceId\\\":\\\""
                + Pattern.quote(traceId)
                + "\\\".*\\\"requestId\\\":\\\""
                + Pattern.quote(requestId)
                + "\\\".*$";
        return Pattern.compile(plain).matcher(output).find()
                || Pattern.compile(structured).matcher(output).find();
    }

    @RestController
    static class TraceProbeController {

        private final Tracer tracer;

        TraceProbeController(Tracer tracer) {
            this.tracer = tracer;
        }

        @GetMapping("/test-only/trace-context")
        ApiResponse<Map<String, String>> traceContext() {
            Span span = tracer.currentSpan();
            return ApiResponse.success(Map.of(
                    "traceId", MDC.get("traceId"),
                    "spanId", MDC.get("spanId"),
                    "requestId", MDC.get(RequestIdFilter.MDC_KEY),
                    "tracerTraceId", span.context().traceId(),
                    "tracerSpanId", span.context().spanId()));
        }
    }
}
