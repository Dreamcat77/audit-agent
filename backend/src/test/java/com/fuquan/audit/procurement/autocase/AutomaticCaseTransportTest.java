package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AutomaticCaseTransportTest {
    @Test void acceptedPostWithLostResponseIsNotRepeatedByAdapterOrActualJdkTransport()throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/v1/materials/extract-fields",exchange->{
            exchange.getRequestBody().readAllBytes();calls.incrementAndGet();exchange.close();
        });server.start();
        try {
            var adapter=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertThatThrownBy(()->adapter.extract("PROCUREMENT_AUTO",UUID.randomUUID().toString(),"a".repeat(64),List.of(new MaterialFieldAiPort.Segment("p1","合成采购需求"))))
                    .isInstanceOf(ExternalServiceException.class);
            assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void acknowledgedEmptyResultIsKeptAsEmptyRatherThanRetriedOrInvented()throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/v1/materials/extract-fields",exchange->{
            exchange.getRequestBody().readAllBytes();calls.incrementAndGet();
            byte[] bytes="{\"code\":200,\"data\":{\"rows\":[],\"model\":\"SYNTHETIC\",\"promptVersion\":\"synthetic-v1\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var adapter=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertThat(adapter.extract("PROCUREMENT_AUTO",UUID.randomUUID().toString(),"a".repeat(64),List.of(new MaterialFieldAiPort.Segment("p1","合成采购需求"))).rows()).isEmpty();
            assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
}
