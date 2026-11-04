package com.fuquan.audit.integration.ai.fastapi;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import static org.junit.jupiter.api.Assertions.*;

class FastApiMaterialPolicyHttpTest {
    @Test void waitsForReadOnlyPolicyWhileSharedServiceIsBusyWithoutDuplicateRequests() throws Exception {
        check(200, "{\"policyId\":\"" + "a".repeat(64) + "\"}", 11000, adapter ->
                assertEquals("a".repeat(64), adapter.policy()));
    }

    @Test void malformedPolicyStillFailsClosed() throws Exception {
        check(200, "{\"policyId\":\"not-a-policy-hash\"}", 0, adapter ->
                assertThrows(IllegalStateException.class, adapter::policy));
    }

    @Test void failedReadDoesNotRetryOrSilentlyUseAnOldPolicy() throws Exception {
        check(503, "{}", 0, adapter ->
                assertThrows(RestClientResponseException.class, adapter::policy));
    }

    private void check(int status, String body, long delay, java.util.function.Consumer<FastApiMaterialFieldAdapter> assertion) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newSingleThreadExecutor();
        var calls = new AtomicInteger();
        server.setExecutor(workers);
        server.createContext("/ai/v1/materials/policy", exchange -> {
            calls.incrementAndGet();
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var adapter = new FastApiMaterialFieldAdapter(RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
            assertion.accept(adapter);
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
