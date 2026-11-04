package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.*;

import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MaterialExtractionCapacityTest {
    @Test void jobsWaitAcrossModulesWithOnlyOneCallAndFailureReturnsItsSlot() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.calls.submit(() -> fixture.extract("BUDGET"));
            var second = fixture.calls.submit(() -> fixture.extract("PROCUREMENT"));
            assertThat(fixture.firstTwo.await(5, TimeUnit.SECONDS)).isTrue();
            var waiting = new CountDownLatch(1);
            var third = fixture.calls.submit(() -> {waiting.countDown(); return fixture.extract("BUDGET");});
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> third.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(fixture.received.get()).isEqualTo(1);
            fixture.release.countDown();
            int successes = 0, failures = 0;
            for (var future : List.of(first, second, third)) {
                try { assertThat(future.get(5, TimeUnit.SECONDS).rows()).isEmpty(); successes++; }
                catch (ExecutionException error) {assertThat(error.getCause()).isInstanceOf(ExternalServiceException.class); failures++;}
            }
            assertThat(successes).isEqualTo(2);
            assertThat(failures).isEqualTo(1);
            assertThat(fixture.peak.get()).isEqualTo(1);
            assertThat(fixture.received.get()).isEqualTo(3);
        }
    }

    @Test void interruptedWaitDoesNotSendHttpOrLeakPermitAndPreservesInterrupt() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.calls.submit(() -> fixture.extract("BUDGET"));
            var second = fixture.calls.submit(() -> fixture.extract("PROCUREMENT"));
            assertThat(fixture.firstTwo.await(5, TimeUnit.SECONDS)).isTrue();
            var seen = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var thread = new Thread(() -> {
                Thread.currentThread().interrupt();
                try { fixture.extract("BUDGET"); }
                catch (Throwable error) { seen.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            thread.start(); thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(seen.get()).isInstanceOf(ExternalServiceException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(interrupted.get()).isTrue();
            assertThat(fixture.received.get()).isEqualTo(1);
            fixture.release.countDown();
            for (var future : List.of(first, second)) {
                try {future.get(5, TimeUnit.SECONDS);} catch (ExecutionException expected) {
                    assertThat(expected.getCause()).isInstanceOf(ExternalServiceException.class);
                }
            }
            assertThat(fixture.extract("PROCUREMENT").rows()).isEmpty();
            assertThat(fixture.received.get()).isEqualTo(3);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService calls = Executors.newFixedThreadPool(3);
        final ExecutorService http = Executors.newCachedThreadPool();
        final CountDownLatch firstTwo = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger received = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
        final HttpServer server;
        final FastApiMaterialFieldAdapter adapter;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(http);
            server.createContext("/ai/v1/materials/extract-fields", exchange -> {
                int sequence = received.incrementAndGet();
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                exchange.getRequestBody().readAllBytes();
                firstTwo.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");
                    byte[] bytes = (sequence == 1 ? "{\"code\":\"MATERIAL_AI_FAILED\"}" :
                        "{\"code\":200,\"data\":{\"rows\":[],\"model\":\"synthetic\",\"promptVersion\":\"test\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(sequence == 1 ? 502 : 200, bytes.length);
                    try (var output = exchange.getResponseBody()) {output.write(bytes);}
                } catch (InterruptedException error) {Thread.currentThread().interrupt();}
                finally {active.decrementAndGet(); exchange.close();}
            });
            server.start();
            adapter = new FastApiMaterialFieldAdapter(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
        }
        MaterialFieldAiPort.Result extract(String kind) {
            return adapter.extract(kind, UUID.randomUUID().toString(), "a".repeat(64),
                List.of(new MaterialFieldAiPort.Segment("synthetic", "synthetic source")));
        }
        public void close() {release.countDown(); calls.shutdownNow(); server.stop(0); http.shutdownNow();}
    }
}
