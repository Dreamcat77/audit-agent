package com.fuquan.audit.integration.ai.fastapi;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SemanticWorkHttpTest {
    final ObjectMapper json=new ObjectMapper();
    final ContractTextAnalysisRequest request=new ContractTextAnalysisRequest(UUID.randomUUID(),List.of(
            new ContractTextAnalysisMaterial(UUID.randomUUID(),"CONTRACT_BODY","bounded-synthetic")));
    @Test void planIsReadOnlyDescriptionAndAdvancePreservesExplicitExecutionIdentity() throws Exception {
        var builder=RestClient.builder().baseUrl("http://127.0.0.1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new FastApiAiReviewAdapter(builder.build());
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-semantic-work/plan"))
                .andExpect(content().json(json.writeValueAsString(request)))
                .andRespond(withSuccess("{\"version\":2}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-semantic-work/advance"))
                .andExpect(content().json("""
                    {"planFingerprint":"%s","expectedOperationFingerprint":"%s","remainingMs":1234,
                     "execute":false,"checkpoints":[]}
                    """.formatted("a".repeat(64),"b".repeat(64))))
                .andRespond(withSuccess("{\"checkpoint\":null,\"next\":{\"ordinal\":1},\"result\":null}",MediaType.APPLICATION_JSON));
        assertThat(adapter.plan(request).path("version").asInt()).isEqualTo(2);
        assertThat(adapter.advance(request,"a".repeat(64),List.of(),"b".repeat(64),1234,false).next().path("ordinal").asInt()).isEqualTo(1);
        server.verify();
    }
    @Test void oversizedOrPrivateErrorBodyNeverBecomesAResultOrExceptionText() {
        var builder=RestClient.builder().baseUrl("http://127.0.0.1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new FastApiAiReviewAdapter(builder.build());
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-semantic-work/plan"))
                .andRespond(withSuccess("private-response-"+"x".repeat(1_048_576),MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.plan(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-response-").hasMessageNotContaining("http://");
    }
    @Test void hardDeadlineDoesNotReleaseSlotOrAcceptLateCompletion() throws Exception {
        var boundary=new BoundedSemanticHttp(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        long started=System.nanoTime();
        try {
            assertThatThrownBy(() -> boundary.call(() -> {
                entered.countDown(); try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return "late";
            },25)).isInstanceOf(IllegalStateException.class);
            assertThat(entered.getCount()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(700);
            assertThatThrownBy(() -> boundary.call(() -> "second",100)).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); }
    }
}
