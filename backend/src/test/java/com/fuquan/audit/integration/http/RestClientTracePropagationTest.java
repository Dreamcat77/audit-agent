package com.fuquan.audit.integration.http;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SpringBootTest
@AutoConfigureObservability
@ActiveProfiles("local")
class RestClientTracePropagationTest {

    @Autowired
    private RestClient.Builder bootManagedRestClientBuilder;

    @Autowired
    private Tracer tracer;

    @Test
    void bootManagedBuilderPropagatesW3cTraceContext() {
        RestClient.Builder builder = bootManagedRestClientBuilder.clone().baseUrl("http://127.0.0.1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        Span parent = tracer.nextSpan().name("rest-client-propagation-test").start();
        String traceId = parent.context().traceId();
        server.expect(requestTo("http://127.0.0.1/probe"))
                .andExpect(header(
                        "traceparent",
                        matchesPattern("^00-" + traceId + "-[0-9a-f]{16}-0[01]$")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            client.get().uri("/probe").retrieve().toBodilessEntity();
        } finally {
            parent.end();
        }

        server.verify();
    }
}
