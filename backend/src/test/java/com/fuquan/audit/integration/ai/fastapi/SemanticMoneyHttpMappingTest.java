package com.fuquan.audit.integration.ai.fastapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SemanticMoneyHttpMappingTest {
    @Test void serverObservationSurvivesTheTypedFinalMappingWithoutChangingTheCandidate() throws Exception {
        var json = new ObjectMapper();
        UUID file = UUID.randomUUID();
        var request = new ContractTextAnalysisRequest(UUID.randomUUID(), List.of(
                new ContractTextAnalysisMaterial(file, "CONTRACT_BODY", "合同金额为67000元")), null, "OBSERVE");
        var builder = RestClient.builder().baseUrl("http://127.0.0.1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-semantic-work/advance"))
                .andRespond(withSuccess("""
                {"result":{"operation":"CONTRACT_TEXT_ANALYSIS","available":true,"summaries":["金额应复核"],
                "hints":[{"category":"DELIVERY_TERMS","attention":"REVIEW","title":"交付计价",
                  "description":"交付报价67000元需核对","evidenceRefs":["%s"],"evidenceItems":[]}],
                "model":"unit-model","durationMs":7,"modelCalls":[],
                "monetaryObservation":{"protocol":"contract-money-observation-v1","mode":"OBSERVE",
                  "warnings":[{"fieldPath":"hints[0].description","errorKind":"MONETARY_VALUE","category":"DELIVERY_TERMS",
                    "textSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ruleSubtypes":["ABSOLUTE_VALUE"]}],"workWarnings":[]}}}
                """.formatted(file), MediaType.APPLICATION_JSON));
        var result = new FastApiAiReviewAdapter(builder.build()).advance(
                request, "a".repeat(64), List.of(), null, 5000, false).result();
        assertThat(result.hints().get(0).description()).isEqualTo("交付报价67000元需核对");
        assertThat(json.valueToTree(result).path("monetaryObservation").path("protocol").asText())
                .isEqualTo("contract-money-observation-v1");
        assertThat(result.monetaryObservation().warnings().get(0).ruleSubtypes()).containsExactly("ABSOLUTE_VALUE");
        server.verify();
    }
}
