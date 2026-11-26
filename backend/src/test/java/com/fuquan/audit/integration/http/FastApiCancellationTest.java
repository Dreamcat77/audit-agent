package com.fuquan.audit.integration.http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class FastApiCancellationTest {
    @ParameterizedTest @ValueSource(strings={"OCR","AI"})
    void interruptionStopsAnActualInFlightHttpCallAndClientRemainsUsable(String kind)throws Exception {
        var received=new CountDownLatch(1);var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/slow",exchange->{received.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.createContext("/next",exchange->{byte[] result="ok".getBytes();exchange.sendResponseHeaders(200,result.length);try(var out=exchange.getResponseBody()){out.write(result);}});
        server.start();var base="http://127.0.0.1:"+server.getAddress().getPort();
        var configuration=new FastApiHttpClientConfiguration();
        RestClient client=kind.equals("OCR")?configuration.ocrRestClient(RestClient.builder(),base,1000,1900000):configuration.aiRestClient(RestClient.builder(),base,1000,60000);
        var failure=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{client.get().uri("/slow").retrieve().body(String.class);}catch(Throwable error){failure.set(error);}});
        try {
            thread.start();assertThat(received.await(3,TimeUnit.SECONDS)).isTrue();
            thread.interrupt();thread.join(3000);
            assertThat(thread.isAlive()).as("cancel must stop the caller, not only mark a Future cancelled").isFalse();
            assertThat(failure.get()).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(client.get().uri("/next").retrieve().body(String.class)).isEqualTo("ok");
        }finally{release.countDown();thread.interrupt();server.stop(0);executor.shutdownNow();thread.join(3000);}
    }
}
