package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MaterialUnitHttpTest {
    @Test void onlyExplicitTimeoutCodeTerminatesLocalWorkAndUnknownErrorsStayUnknown() throws Exception {
        var code=new java.util.concurrent.atomic.AtomicReference<>("MATERIAL_AI_CALL_TIMEOUT");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/v1/materials/extract-fields",e->{e.getRequestBody().readAllBytes();byte[] result=("{\"code\":\""+code.get()+"\"}").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(504,result.length);e.getResponseBody().write(result);e.close();});
        server.start();
        try {
            var adapter=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertThatThrownBy(()->adapter.extractUnit("BUDGET","synthetic","b".repeat(64),List.of(new Segment("p1","合成")),"a".repeat(64),7,1000))
                .isInstanceOf(com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated.class).hasMessageContaining("MATERIAL_CALL_TIMEOUT");
            code.set("ARBITRARY_TIMEOUT_DESCRIPTION");
            assertThatThrownBy(()->adapter.extractUnit("BUDGET","synthetic","b".repeat(64),List.of(new Segment("p1","合成")),"a".repeat(64),7,1000))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        } finally {server.stop(0);}
    }
    @Test void realHttpTransmitsFrozenPolicyAndRemainingBudgetsAndReturnsUsage() throws Exception {
        var calls=new AtomicInteger();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/v1/materials/policy",e->{
            byte[] result=("{\"policyId\":\""+"a".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);e.close();});
        server.createContext("/ai/v1/materials/extract-fields",e->{
            calls.incrementAndGet();var request=json.readTree(e.getRequestBody());
            assertThat(request.get("policyId").asText()).isEqualTo("a".repeat(64));
            assertThat(request.get("callLimit").asInt()).isEqualTo(7);
            assertThat(request.get("timeLimitMillis").asInt()).isBetween(1,10000);
            byte[] result="{\"code\":200,\"data\":{\"rows\":[],\"model\":\"test\",\"promptVersion\":\"v6\",\"callCount\":2}}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);e.close();});
        server.start();
        try {
            var adapter=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            var policy=adapter.policy();
            var result=adapter.extractUnit("BUDGET","synthetic","b".repeat(64),List.of(new Segment("p1","合成材料")),policy,7,10000);
            assertThat(result.callCount()).isEqualTo(2);assertThat(calls).hasValue(1);
        } finally {server.stop(0);}
    }
    @Test void connectionLostAfterReceivingBodyIsNeverAutomaticallyReplayed() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/v1/materials/extract-fields",e->{e.getRequestBody().readAllBytes();calls.incrementAndGet();e.close();});
        server.start();
        try {
            var adapter=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertThatThrownBy(()->adapter.extractUnit("PROCUREMENT","synthetic","b".repeat(64),
                List.of(new Segment("p1","合成材料")),"a".repeat(64),7,10000)).isInstanceOf(RuntimeException.class);
            assertThat(calls).hasValue(1);
        } finally {server.stop(0);}
    }
}
