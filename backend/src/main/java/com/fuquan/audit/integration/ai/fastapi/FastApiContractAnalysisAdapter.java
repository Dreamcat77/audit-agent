package com.fuquan.audit.integration.ai.fastapi;

import com.fuquan.audit.contract.application.analysis.ContractAnalysisAiPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile({"local-dev & local-ai","dev29","customer"})
public class FastApiContractAnalysisAdapter implements ContractAnalysisAiPort {
    private final RestClient client;
    public FastApiContractAnalysisAdapter(@Qualifier("aiRestClient") RestClient client){
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(170));
        this.client=client.mutate().requestFactory(factory).build();
    }
    public Result analyze(Request request){
        try{return client.post().uri("/ai/v1/contracts/analyze-case").body(request).retrieve().body(Result.class);}
        catch(org.springframework.web.client.RestClientResponseException external){
            if(external.getStatusCode().value()==502) {
                // Parse a single known code; never log or retain the error body.
                try {
                    var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(external.getResponseBodyAsByteArray());
                    if(body.path("code").asText().equals("CONTRACT_ANALYSIS_OUTPUT_LIMIT"))
                        throw new com.fuquan.audit.contract.application.analysis.ContractAnalysisOutputLimitException();
                } catch(java.io.IOException malformed) { /* Keep the original status. */ }
            }
            throw external;
        }
        catch(org.springframework.web.client.ResourceAccessException external){throw external;}
        catch(org.springframework.web.client.RestClientException invalid){throw new IllegalArgumentException("MODEL_RESPONSE_SCHEMA_INVALID",invalid);}
    }
}
