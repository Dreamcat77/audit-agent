package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MaterialConnectionRecoveryTest {
    @Test
    void httpOrSchemaFailuresAreNeverRetried() throws Exception {
        for (int status : new int[]{502, 200}) {
            var bodies = new CopyOnWriteArrayList<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ai/v1/materials/extract-fields", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = "{\"code\":502,\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var adapter = new FastApiMaterialFieldAdapter(RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
                assertThatThrownBy(() -> adapter.extract("BUDGET", "synthetic-id", "a".repeat(64),
                        List.of(new MaterialFieldAiPort.Segment("p1", "synthetic evidence"))))
                        .isInstanceOf(ExternalServiceException.class);
                assertThat(bodies).hasSize(1);
            } finally { server.stop(0); }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void droppedConnectionRetriesSameSourceOnceAndNeverReturnsFakeSuccess(boolean recover) throws Exception {
        var bodies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/v1/materials/extract-fields", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Connection")).isEqualTo("close");
            if (bodies.size() == 1 || !recover) {exchange.close();return;}
            byte[] bytes="{\"code\":200,\"data\":{\"rows\":[],\"model\":\"synthetic\",\"promptVersion\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var adapter = new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
            Runnable run = () -> adapter.extract("PROCUREMENT", "synthetic-id", "a".repeat(64), List.of(new MaterialFieldAiPort.Segment("p1", "synthetic evidence")));
            if (recover) assertThatCode(run::run).doesNotThrowAnyException();
            else assertThatThrownBy(run::run).isInstanceOf(ExternalServiceException.class);
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
        } finally {server.stop(0);}
    }
}
