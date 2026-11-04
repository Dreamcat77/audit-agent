package com.fuquan.audit.integration.ai.fastapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentException;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentPort.Request;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentPort.Segment;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FastApiDocumentRoleAssessmentAdapterTest {
    private MockRestServiceServer server;
    private FastApiDocumentRoleAssessmentAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new FastApiDocumentRoleAssessmentAdapter(builder.build(), "qwen-test");
    }

    @Test
    void validatesParseBoundEvidenceAndModelProvenance() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/document-role"))
                .andExpect(content().json("""
                        {"fileId":"00000000-0000-0000-0000-000000000101",
                         "parseResultId":"00000000-0000-0000-0000-000000000102",
                         "parseVersion":1,"canonicalTextSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                         "segments":[{"segmentId":"segment-1","sourceLocator":"parse-result:00000000-0000-0000-0000-000000000102:canonical-codepoint:0-4","textSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","text":"采购合同"}]}
                        """))
                .andRespond(withSuccess("""
                        {"code":200,"message":"success","data":{
                          "proposedRole":"CONTRACT_BODY","confidence":0.91,
                          "candidateRoles":[{"role":"CONTRACT_BODY","confidence":0.91,"reasonCode":"CONTRACT_TERMS"}],
                          "reasonCodes":["CONTRACT_TERMS"],"evidenceSegmentIds":["segment-1"],
                          "schemaVersion":"document-role-v1","promptVersion":"document-role-prompt-v1",
                          "modelProvider":"OPENAI_COMPATIBLE","modelName":"qwen-test",
                          "modelRevision":"NOT_DECLARED_BY_PROVIDER"}}
                        """, MediaType.APPLICATION_JSON));

        var result = adapter.assess(request());

        assertThat(result.proposedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(result.confidence()).isEqualByComparingTo("0.91");
        assertThat(result.evidenceSegmentIds()).containsExactly("segment-1");
        assertThat(result.provenance()).isEqualTo(adapter.configuredProvenance());
        server.verify();
    }

    @Test
    void rejectsProviderCreatedSegmentIdentity() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/document-role"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"success","data":{
                          "proposedRole":"CONTRACT_BODY","confidence":0.91,
                          "candidateRoles":[{"role":"CONTRACT_BODY","confidence":0.91,"reasonCode":"CONTRACT_TERMS"}],
                          "reasonCodes":["CONTRACT_TERMS"],"evidenceSegmentIds":["invented"],
                          "schemaVersion":"document-role-v1","promptVersion":"document-role-prompt-v1",
                          "modelProvider":"OPENAI_COMPATIBLE","modelName":"qwen-test",
                          "modelRevision":"NOT_DECLARED_BY_PROVIDER"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.assess(request()))
                .isInstanceOf(DocumentRoleAssessmentException.class)
                .extracting("reason")
                .isEqualTo(DocumentRoleAssessmentException.Reason.INVALID_RESPONSE);
    }

    private static Request request() {
        UUID parse = UUID.fromString("00000000-0000-0000-0000-000000000102");
        return new Request(UUID.fromString("00000000-0000-0000-0000-000000000101"), parse, 1,
                "a".repeat(64), List.of(new Segment("segment-1",
                "parse-result:%s:canonical-codepoint:0-4".formatted(parse), "b".repeat(64),
                0, 4, "采购合同")));
    }
}
