package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.privacy.ClientReviewIdentity;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

class ClientIdentityHttpBoundaryTest {
    @Test void onlyAiReceivesInternalIdentityAndMutationPreservesProtection() throws Exception {
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<ClientReviewIdentity>)mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(new ClientReviewIdentity(()->List.of("北辰市公安局")));
        var boundary=new ClientIdentityHttpBoundary(provider);
        var received=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/test",exchange->{
            received.set(exchange.getRequestHeaders().getFirst(ClientReviewIdentity.HEADER));
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        server.start();
        try {
            var raw=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            assertThat(boundary.postProcessAfterInitialization(raw,"ocrRestClient")).isSameAs(raw);
            var ai=(RestClient)boundary.postProcessAfterInitialization(raw,"aiRestClient");
            ai.mutate().build().post().uri("/test").body("{}").retrieve().toBodilessEntity();
            assertThat(received.get()).isNotBlank();
            assertThat(new String(Base64.getDecoder().decode(received.get()),StandardCharsets.UTF_8)).contains("北辰市公安局");
        } finally {server.stop(0);}
    }
}
