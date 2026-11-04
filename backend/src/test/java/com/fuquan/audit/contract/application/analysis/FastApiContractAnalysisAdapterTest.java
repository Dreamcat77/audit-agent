package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.integration.ai.fastapi.FastApiContractAnalysisAdapter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;

class FastApiContractAnalysisAdapterTest {
    ContractAnalysisAiPort.Request request(){return new ContractAnalysisAiPort.Request(UUID.randomUUID(),"a".repeat(64),"FILE",List.of(new ContractAnalysisAiPort.Document(UUID.randomUUID(),"合成条款")));}
    @Test void receivedMalformedJsonIsKnownInvalidRatherThanAnUnknownExternalOutcome(){
        var client=RestClient.builder().baseUrl("http://synthetic.invalid").requestInterceptor((req,body,next)->{
            var response=new MockClientHttpResponse("{invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8),HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);return response;
        }).build();
        assertThatThrownBy(()->new FastApiContractAnalysisAdapter(client).analyze(request()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("MODEL_RESPONSE_SCHEMA_INVALID");
    }
    @Test void timeoutRemainsUnknownAndIsNotReclassifiedAsMalformedData(){
        var timeout=new ResourceAccessException("synthetic timeout");
        var client=RestClient.builder().baseUrl("http://synthetic.invalid").requestInterceptor((req,body,next)->{throw timeout;}).build();
        assertThatThrownBy(()->new FastApiContractAnalysisAdapter(client).analyze(request())).isSameAs(timeout);
    }
    @Test void providerHttpStatusIsPreserved(){
        var client=RestClient.builder().baseUrl("http://synthetic.invalid").requestInterceptor((req,body,next)->new MockClientHttpResponse(new byte[0],HttpStatus.SERVICE_UNAVAILABLE)).build();
        assertThatThrownBy(()->new FastApiContractAnalysisAdapter(client).analyze(request())).isInstanceOf(HttpServerErrorException.class);
    }
    @Test void onlyAnExplicitProvenOutputLimitGetsTheSplittableFailureType(){
        for(String code:java.util.List.of("CONTRACT_ANALYSIS_OUTPUT_LIMIT","CONTRACT_ANALYSIS_MODEL_FAILED")){
            var bytes=("{\"code\":\""+code+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var client=RestClient.builder().baseUrl("http://synthetic.invalid").requestInterceptor((req,body,next)->new MockClientHttpResponse(bytes,HttpStatus.BAD_GATEWAY)).build();
            assertThatThrownBy(()->new FastApiContractAnalysisAdapter(client).analyze(request()))
                .isInstanceOf(code.endsWith("OUTPUT_LIMIT")?ContractAnalysisOutputLimitException.class:HttpServerErrorException.class);
        }
    }
}
