package com.fuquan.audit.integration.ai.fastapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidateException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.ContextCode;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.FactType;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class FastApiContractFactCandidateAdapterTest {
    private static final String TEXT = "合同总金额人民币67000.00元";
    private static final String PROVIDER_BASE_URL = "http://provider.internal:11434/v1";
    private MockRestServiceServer server;
    private FastApiContractFactCandidateAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new FastApiContractFactCandidateAdapter(builder.build(), "fact-model-test", PROVIDER_BASE_URL);
    }

    @Test
    void sendsServerOwnedColumnOrderWithoutReinterpretingTheSource() {
        var original = request();
        String text = "X-1\t服务\t100.00\t2\t200.00\n";
        var segment = new Segment("segment-1", "parse-result:" + original.parseResultId()
                + ":canonical-codepoint:0-" + text.length(), sha256(text), 0, text.length(), text,
                "row:" + "c".repeat(64), List.of("rowId", "name", "unitPrice", "quantity", "declaredAmount"));
        var mapped = new Request(original.projectId(), original.fileId(), original.fileSha256(),
                original.parseResultId(), original.parseVersion(), sha256(text), List.of(segment), Scope.LINE_ITEMS);
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andExpect(content().json("""
                        {"scope":"LINE_ITEMS","evidenceCatalog":[{"evidenceId":"segment-1",
                          "rowIdentity":"row:%s",
                          "lineItemColumns":["rowId","name","unitPrice","quantity","declaredAmount"],"text":"%s"}]}
                        """.formatted("c".repeat(64), text.replace("\t", "\\t").replace("\n", "\\n"))))
                .andRespond(withSuccess(successResponse("fact-model-test", "67000.00")
                        .replace("\"scope\":\"CORE\"", "\"scope\":\"LINE_ITEMS\"")
                        .replaceFirst("(?s)\\\"candidates\\\":\\[.*?\\],", "\"candidates\":[],"), MediaType.APPLICATION_JSON));
        assertThat(adapter.extract(mapped).candidates()).isEmpty();
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "LINE_ITEM_QUANTITY,100.00,false", "LINE_ITEM_UNIT_PRICE,2,false",
            "LINE_ITEM_NAME,维护服务,false", "LINE_ITEM_QUANTITY,2.00,true",
            "LINE_ITEM_NAME,维护服务100,true", "LINE_ITEM_DECLARED_AMOUNT,200.00,true",
            "LINE_ITEM_KEY,X-1,false"})
    void mappedColumnGroundingCannotBeBypassedByLegacyCandidates(String type, String provided, boolean valid)
            throws Exception {
        var original = request();
        String text = "X-1\t维护服务100\t2\t100.00\t200.00\n";
        var segment = new Segment("segment-1", "parse-result:" + original.parseResultId()
                + ":canonical-codepoint:0-" + text.length(), sha256(text), 0, text.length(), text,
                "row:" + "c".repeat(64), List.of("rowId", "name", "quantity", "unitPrice", "declaredAmount"));
        var mapped = new Request(original.projectId(), original.fileId(), original.fileSha256(),
                original.parseResultId(), original.parseVersion(), sha256(text), List.of(segment), Scope.LINE_ITEMS);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.readTree(successResponse("fact-model-test", "67000.00"));
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) response.get("data");
        data.put("scope", "LINE_ITEMS");
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) data.get("candidates").get(0);
        candidate.put("factType", type).put("contextCode", "CONTRACT_LINE_ITEM")
                .put("groupKey", com.fuquan.audit.contract.application.project.factai.ContractFactSegmenter.lineGroup(segment.rowIdentity()))
                .put("sourceLocator", segment.sourceLocator()).put("excerpt", text)
                .put("excerptStart", 0).put("excerptEnd", text.length());
        if (type.equals("LINE_ITEM_NAME") || type.equals("LINE_ITEM_KEY")) candidate.put("valueText", provided).putNull("valueDecimal");
        else candidate.put("valueDecimal", provided).putNull("valueText");
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        if (valid) assertThat(adapter.extract(mapped).candidates()).hasSize(1);
        else assertThatThrownBy(() -> adapter.extract(mapped)).isInstanceOf(ContractFactCandidateException.class)
                .extracting("reason").isEqualTo(ContractFactCandidateException.Reason.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void validatesExactParseBoundCandidateAndLogsSafeCorrelatedLifecycle(CapturedOutput output) {
        MDC.put("requestId", "request-safe-1");
        MDC.put("traceId", "trace-safe-1");
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andExpect(content().json("""
                        {"projectId":"00000000-0000-0000-0000-000000000100",
                         "fileId":"00000000-0000-0000-0000-000000000101",
                         "fileSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                         "parseResultId":"00000000-0000-0000-0000-000000000102",
                         "parseVersion":3,
                         "canonicalTextSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                         "scope":"CORE",
                         "evidenceCatalog":[{"evidenceId":"segment-1",
                           "sourceLocator":"parse-result:00000000-0000-0000-0000-000000000102:canonical-codepoint:0-17",
                           "textSha256":"%s","startOffset":0,"endOffset":17,"text":"%s"}]}
                        """.formatted(sha256(TEXT), TEXT)))
                .andRespond(withSuccess(successResponse("fact-model-test", "67000.00"),
                        MediaType.APPLICATION_JSON));

        var result = adapter.extract(request());

        assertThat(result.provenance()).isEqualTo(adapter.configuredProvenance());
        assertThat(result.durationMs()).isEqualTo(9);
        assertThat(result.scope()).isEqualTo(Scope.CORE);
        assertThat(result.batchStatus()).isEqualTo(BatchStatus.PARTIAL);
        assertThat(result.rejections()).isEmpty();
        assertThat(result.modelCalls()).singleElement().satisfies(call -> {
            assertThat(call.ordinal()).isEqualTo(1);
            assertThat(call.finishReason()).isEqualTo("STOP");
            assertThat(call.totalTokens()).isEqualTo(160);
        });
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.factType()).isEqualTo(FactType.CONTRACT_AMOUNT);
            assertThat(candidate.contextCode()).isEqualTo(ContextCode.CONTRACT_AMOUNT);
            assertThat(candidate.valueDecimal()).isEqualByComparingTo("67000.00");
            assertThat(candidate.excerpt()).isEqualTo("67000.00");
        });
        assertThat(output).contains("event=AI_HTTP_STARTED")
                .contains("event=AI_HTTP_COMPLETED")
                .contains("stage=AI_HTTP")
                .contains("outcome=HTTP_RESPONSE_RECEIVED")
                .contains("requestId=request-safe-1")
                .contains("traceId=trace-safe-1")
                .contains("projectId=00000000-0000-0000-0000-000000000100")
                .contains("fileId=00000000-0000-0000-0000-000000000101")
                .doesNotContain(TEXT);
        server.verify();
        MDC.clear();
    }

    @Test
    void rejectsDifferentRuntimeModelOrFabricatedExcerpt() {
        for (String response : List.of(
                successResponse("different-model", "67000.00"),
                successResponse("fact-model-test", "69000.00"))) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1");
            MockRestServiceServer isolated = MockRestServiceServer.bindTo(builder).build();
            FastApiContractFactCandidateAdapter subject =
                    new FastApiContractFactCandidateAdapter(builder.build(), "fact-model-test", PROVIDER_BASE_URL);
            isolated.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> subject.extract(request()))
                    .isInstanceOf(ContractFactCandidateException.class)
                    .extracting("reason")
                    .isEqualTo(ContractFactCandidateException.Reason.INVALID_RESPONSE);
            isolated.verify();
        }
    }

    @Test
    void mapsSafePartialRejectionWithoutDroppingAcceptedCandidate() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(withSuccess(successResponse("fact-model-test", "67000.00")
                        .replace("\"rejections\":[]", "\"rejections\":[{\"providerOrdinal\":2,"
                                + "\"stage\":\"TYPED_SCHEMA\",\"reasonCode\":\"INVALID_TYPED_SCHEMA\","
                                + "\"safeShapeHash\":\"" + "d".repeat(64) + "\"}]") ,
                        MediaType.APPLICATION_JSON));

        var result = adapter.extract(request());

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.providerOrdinal()).isEqualTo(2);
            assertThat(rejection.reasonCode()).isEqualTo("INVALID_TYPED_SCHEMA");
        });
    }

    @Test
    void mapsOneBoundedTypedSchemaRepairAndItsOriginalRejection() {
        String repaired = successResponse("fact-model-test", "67000.00")
                .replace("\"rejections\":[]", "\"rejections\":[{\"providerOrdinal\":1,"
                        + "\"stage\":\"TYPED_SCHEMA\",\"reasonCode\":\"INVALID_TYPED_SCHEMA\","
                        + "\"safeShapeHash\":\"" + "d".repeat(64) + "\"}]");
        repaired = repaired.replaceFirst(
                "(?s)\\\"modelCalls\\\":\\[\\{.*?\\\"totalTokens\\\":160}\\]",
                "\"modelCalls\":[{\"ordinal\":1,\"phase\":\"TRANSPORT\",\"outcome\":\"FAILED\","
                        + "\"safeReason\":\"INVALID_TYPED_SCHEMA\",\"durationMs\":9,\"finishReason\":\"STOP\","
                        + "\"promptTokens\":120,\"completionTokens\":40,\"totalTokens\":160},"
                        + "{\"ordinal\":2,\"phase\":\"REPAIR\",\"outcome\":\"SUCCEEDED\","
                        + "\"safeReason\":null,\"durationMs\":8,\"finishReason\":\"STOP\","
                        + "\"promptTokens\":100,\"completionTokens\":30,\"totalTokens\":130}]")
                .replace("\"durationMs\":9}}", "\"durationMs\":17}}");
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(withSuccess(repaired, MediaType.APPLICATION_JSON));

        var result = adapter.extract(request());

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.rejections()).singleElement()
                .satisfies(value -> assertThat(value.reasonCode()).isEqualTo("INVALID_TYPED_SCHEMA"));
        assertThat(result.modelCalls()).satisfiesExactly(first -> {
            assertThat(first.ordinal()).isEqualTo(1);
            assertThat(first.phase().name()).isEqualTo("TRANSPORT");
            assertThat(first.outcome().name()).isEqualTo("FAILED");
            assertThat(first.safeReason()).isEqualTo("INVALID_TYPED_SCHEMA");
        }, second -> {
            assertThat(second.ordinal()).isEqualTo(2);
            assertThat(second.phase().name()).isEqualTo("REPAIR");
            assertThat(second.outcome().name()).isEqualTo("SUCCEEDED");
            assertThat(second.safeReason()).isNull();
        });
        assertThat(result.durationMs()).isEqualTo(17);
    }

    private static Request request() {
        UUID parse = UUID.fromString("00000000-0000-0000-0000-000000000102");
        return new Request(
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                UUID.fromString("00000000-0000-0000-0000-000000000101"), "a".repeat(64),
                parse, 3, "b".repeat(64),
                List.of(new Segment("segment-1",
                        "parse-result:%s:canonical-codepoint:0-17".formatted(parse),
                        sha256(TEXT), 0, 17, TEXT)));
    }

    @Test
    void distinguishesHttp200BusinessRefusalFromTransportAndMalformedResults() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(withSuccess("{\"code\":422,\"message\":\"MODEL_REFUSED\",\"data\":null}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOf(ContractFactCandidateException.class)
                .extracting(failure -> ((ContractFactCandidateException) failure).getReason().name())
                .isEqualTo("BUSINESS_REFUSED");
        server.verify();
    }

    @Test
    void connectionResetAfterSendingCannotBeReportedAsKnownUnavailable() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(request -> { throw new java.net.SocketException("synthetic response reset"); });
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOf(ContractFactCandidateException.class)
                .extracting(failure -> ((ContractFactCandidateException) failure).getReason().name())
                .isEqualTo("OUTCOME_UNKNOWN");
        server.verify();
    }

    @Test
    void downstreamInternalErrorDoesNotProveTheModelWasNeverCalled() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INTERNAL_ERROR\"}"));
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOf(ContractFactCandidateException.class)
                .extracting(failure -> ((ContractFactCandidateException) failure).getReason().name())
                .isEqualTo("OUTCOME_UNKNOWN");
        server.verify();
    }

    @Test
    void explicitDisabledEnvelopeProvesTheModelWasNotCalled() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CONTRACT_FACT_EXTRACTION_DISABLED\"}"));
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOf(ContractFactCandidateException.class).extracting("reason")
                .isEqualTo(ContractFactCandidateException.Reason.UNAVAILABLE);
        server.verify();
    }

    @Test
    void connectionRefusedBeforeSendingRemainsKnownUnavailable() {
        server.expect(requestTo("http://127.0.0.1/ai/v2/agent/contract-fact-candidates"))
                .andRespond(request -> { throw new java.net.ConnectException("synthetic connect refused"); });
        assertThatThrownBy(() -> adapter.extract(request()))
                .isInstanceOf(ContractFactCandidateException.class).extracting("reason")
                .isEqualTo(ContractFactCandidateException.Reason.UNAVAILABLE);
        server.verify();
    }

    private static String successResponse(String model, String excerpt) {
        int start = TEXT.indexOf("67000.00");
        return """
                {"code":200,"message":"success","data":{
                  "candidates":[{"candidateKey":"cfc-v2-%s","factType":"CONTRACT_AMOUNT",
                    "groupKey":null,"valueText":null,"valueDecimal":"67000.00",
                    "segmentId":"segment-1",
                    "sourceLocator":"parse-result:00000000-0000-0000-0000-000000000102:canonical-codepoint:0-17",
                    "excerpt":"%s","excerptStart":%d,"excerptEnd":%d,
                    "contextCode":"CONTRACT_AMOUNT","confidence":0.96}],
                  "rejections":[],"scope":"CORE","batchStatus":"PARTIAL",
                  "modelCalls":[{"ordinal":1,"phase":"TRANSPORT","outcome":"SUCCEEDED",
                    "safeReason":null,"durationMs":9,"finishReason":"STOP",
                    "promptTokens":120,"completionTokens":40,"totalTokens":160}],
                  "schemaVersion":"contract-fact-candidates-v2",
                  "promptVersion":"contract-fact-prompt-v2.3","modelProvider":"OPENAI_COMPATIBLE_ENDPOINT_SHA256:%s",
                  "modelName":"%s","modelRevision":"NOT_DECLARED_BY_PROVIDER","durationMs":9}}
                """.formatted("c".repeat(64), excerpt, start, start + excerpt.length(),
                        sha256(PROVIDER_BASE_URL), model);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
