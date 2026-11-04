package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

class FastApiPipelineDiagnosticsTest {
    @Test void legacyOcrWindowCannotAbandonTheWorkerButModelTimeoutsRemainIndependent() {
        assertThat(FastApiHttpClientConfiguration.ocrReadTimeoutMillis(120000)).isEqualTo(1900000);
        assertThat(FastApiHttpClientConfiguration.ocrReadTimeoutMillis(2400000)).isEqualTo(2400000);
        assertThatThrownBy(()->FastApiHttpClientConfiguration.ocrReadTimeoutMillis(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void materialExtractionPreservesRequestCorrelationAfterRequestFactoryOverride() throws Exception {
        var id = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/v1/materials/extract-fields", exchange -> {
            id.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes="{\"code\":200,\"data\":{\"rows\":[],\"model\":\"synthetic\",\"promptVersion\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        MDC.put("requestId", "synthetic-material-correlation");
        try {
            var client = new FastApiHttpClientConfiguration().aiRestClient(RestClient.builder(),
                    "http://127.0.0.1:"+server.getAddress().getPort(), 5000, 960000);
            var result = new FastApiMaterialFieldAdapter(client).extract("PROCUREMENT", UUID.randomUUID().toString(),
                    "a".repeat(64), List.of(new MaterialFieldAiPort.Segment("docx/p:1", "synthetic text")));
            assertThat(result.rows()).isEmpty();
            assertThat(id.get()).isEqualTo("synthetic-material-correlation");
        } finally { MDC.remove("requestId"); server.stop(0); }
    }
    @Test void ocrMultipartDoesNotGainAnInterceptorThatBuffersTheDocument() {
        var builder=mock(RestClient.Builder.class, RETURNS_SELF);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(RestClient.class));
        new FastApiHttpClientConfiguration().ocrRestClient(builder,"http://localhost",5000,120000);
        verify(builder,never()).requestInterceptor(any());
    }
}
