package com.fuquan.audit.integration.ai.fastapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisItem;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementTenderReviewAiPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FastApiAiReviewAdapterTest {

    private MockRestServiceServer server;
    private FastApiAiReviewAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new FastApiAiReviewAdapter(builder.build());
    }

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void postsContractTextRequestToItsTypedPathAndMapsProvenance() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID fileId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        MDC.put(RequestIdFilter.MDC_KEY, "contract-ai-request");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestIdFilter.HEADER_NAME, "contract-ai-request"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "projectId": "00000000-0000-0000-0000-000000000101",
                          "materials": [{
                            "fileId": "00000000-0000-0000-0000-000000000102",
                            "category": "CONTRACT_BODY",
                            "text": "合同工期条款"
                          }]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "CONTRACT_TEXT_ANALYSIS",
                            "summaries": ["合同范围已识别"],
                            "hints": [{
                              "category": "DELIVERY_TERMS",
                              "attention": "REVIEW",
                              "title": "工期表述",
                              "description": "建议人工核对开工条件",
                              "evidenceRefs": ["00000000-0000-0000-0000-000000000102"],
                              "evidenceItems": [{
                                "fileId": "00000000-0000-0000-0000-000000000102",
                                "status": "REVIEW",
                                "excerpt": "合同 工期条款",
                                "locatorAvailable": true
                              }]
                            }],
                            "model": "qwen-test",
                            "durationMs": 7,
                            "modelCalls": [{
                              "ordinal": 1,
                              "phase": "TRANSPORT",
                              "outcome": "SUCCEEDED",
                              "durationMs": 7,
                              "finishReason": "STOP",
                              "promptTokens": 100,
                              "completionTokens": 20,
                              "totalTokens": 120,
                              "provider": "OPENAI_COMPATIBLE",
                              "model": "qwen-test"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ContractTextAnalysisResult result = adapter.analyzeContractText(
                new ContractTextAnalysisRequest(projectId, List.of(
                        new ContractTextAnalysisMaterial(fileId, "CONTRACT_BODY", "合同工期条款"))));

        assertThat(result.available()).isTrue();
        assertThat(result.summaries()).containsExactly("合同范围已识别");
        assertThat(result.hints()).singleElement().satisfies(hint -> {
            assertThat(hint.category()).isEqualTo(ContractTextAnalysisResult.SemanticCategory.DELIVERY_TERMS);
            assertThat(hint.attention()).isEqualTo(ContractTextAnalysisResult.Attention.REVIEW);
            assertThat(hint.title()).isEqualTo("工期表述");
            assertThat(hint.evidenceRefs()).containsExactly(fileId);
            assertThat(hint.evidenceItems()).singleElement().satisfies(item -> {
                assertThat(item.fileId()).isEqualTo(fileId);
                assertThat(item.status()).isEqualTo(ContractTextAnalysisResult.EvidenceStatus.REVIEW);
                assertThat(item.excerpt()).isEqualTo("合同 工期条款");
                assertThat(item.locatorAvailable()).as("provider locator claims are never trusted")
                        .isFalse();
            });
        });
        assertThat(result.model()).isEqualTo("qwen-test");
        assertThat(result.durationMs()).isEqualTo(7L);
        assertThat(result.modelCalls()).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(ContractTextAnalysisResult.CallOutcome.SUCCEEDED);
            assertThat(call.finishReason()).isEqualTo("STOP");
            assertThat(call.totalTokens()).isEqualTo(120);
        });
        server.verify();
    }

    @Test
    void rejectsInventedContractEvidenceReference() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID selectedFileId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "CONTRACT_TEXT_ANALYSIS",
                            "summaries": ["交付条款已提取"],
                            "hints": [{
                              "category": "DELIVERY_TERMS",
                              "attention": "INFO",
                              "title": "交付期",
                              "description": "文本载明交付期",
                              "evidenceRefs": ["00000000-0000-0000-0000-000000000999"]
                            }],
                            "model": "qwen-test",
                            "durationMs": 3
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyzeContractText(
                new ContractTextAnalysisRequest(projectId, List.of(
                        new ContractTextAnalysisMaterial(selectedFileId, "CONTRACT_BODY", "交付期20个工作日")))))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("external service unavailable")
                .hasMessageNotContaining("00000000-0000-0000-0000-000000000999");
        server.verify();
    }

    @Test
    void mapsTheThreePm44SemanticCategoriesFromTheTypedFastApiContract() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000121");
        UUID fileId = UUID.fromString("00000000-0000-0000-0000-000000000122");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "CONTRACT_TEXT_ANALYSIS",
                            "summaries": ["已完成语义审查"],
                            "hints": [
                              {"category":"CONTRACT_STRUCTURE","attention":"INFO","title":"合同结构","description":"主要要素已识别","evidenceRefs":["00000000-0000-0000-0000-000000000122"]},
                              {"category":"BREACH_LIABILITY","attention":"REVIEW","title":"违约责任","description":"比例约定存在差异","evidenceRefs":["00000000-0000-0000-0000-000000000122"]},
                              {"category":"TERM_TERMINATION","attention":"REVIEW","title":"期限终止","description":"解除条件建议复核","evidenceRefs":["00000000-0000-0000-0000-000000000122"]}
                            ],
                            "model": "qwen-test",
                            "durationMs": 9
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ContractTextAnalysisResult result = adapter.analyzeContractText(
                new ContractTextAnalysisRequest(projectId, List.of(
                        new ContractTextAnalysisMaterial(fileId, "CONTRACT_BODY", "合同条款"))));

        assertThat(result.hints()).extracting(ContractTextAnalysisResult.Hint::category)
                .containsExactly(
                        ContractTextAnalysisResult.SemanticCategory.CONTRACT_STRUCTURE,
                        ContractTextAnalysisResult.SemanticCategory.BREACH_LIABILITY,
                        ContractTextAnalysisResult.SemanticCategory.TERM_TERMINATION);
        server.verify();
    }

    @Test
    void postsBudgetSemanticRequestWithoutMonetaryFieldsToDistinctPath() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/budget-semantic-analysis"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "sourceId": "00000000-0000-0000-0000-000000000201",
                          "items": [{
                            "itemId": "00000000-0000-0000-0000-000000000202",
                            "description": "彩色打印设备",
                            "currentCategory": "设备"
                          }]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "BUDGET_SEMANTIC_ANALYSIS",
                            "items": [{
                              "itemId": "00000000-0000-0000-0000-000000000202",
                              "suggestedCategory": "办公设备",
                              "hint": "描述包含打印设备"
                            }],
                            "model": "qwen-test",
                            "durationMs": 5
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        BudgetSemanticAnalysisResult result = adapter.analyzeBudgetSemantics(
                new BudgetSemanticAnalysisRequest(sourceId, List.of(
                        new BudgetSemanticAnalysisItem(itemId, "彩色打印设备", "设备"))));

        assertThat(result.available()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo(itemId);
            assertThat(item.suggestedCategory()).isEqualTo("办公设备");
        });
        assertThat(result.model()).isEqualTo("qwen-test");
        assertThat(result.durationMs()).isEqualTo(5L);
        server.verify();
    }

    @Test
    void mapsNodeSpecificBudgetResultWhenOnlyExceptionDescriptionFieldsAreReturned() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000211");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000212");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/budget-semantic-analysis"))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "BUDGET_SEMANTIC_ANALYSIS",
                            "items": [{
                              "itemId": "00000000-0000-0000-0000-000000000212",
                              "enhancedDescription": "该行的供应商名称为空，无法完成供应商信息核对。",
                              "suggestion": "补充当前行的供应商名称后重新核对。"
                            }],
                            "model": "qwen-test",
                            "durationMs": 12
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        BudgetSemanticAnalysisResult result = adapter.analyzeBudgetSemantics(
                new BudgetSemanticAnalysisRequest(sourceId, List.of(
                        new BudgetSemanticAnalysisItem(
                                itemId, "供应商名称缺失", null,
                                "EXCEPTION_DESCRIPTION", null, null, null, null,
                                "REQUIRED_FIELDS", "missingFields=供应商名称"))));

        assertThat(result.available()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.suggestedCategory()).isNull();
            assertThat(item.hint()).isNull();
            assertThat(item.enhancedDescription())
                    .isEqualTo("该行的供应商名称为空，无法完成供应商信息核对。");
            assertThat(item.suggestion()).isEqualTo("补充当前行的供应商名称后重新核对。");
        });
        server.verify();
    }

    @Test
    void reusesBudgetSemanticEndpointForProcurementExplanations() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID summaryId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/budget-semantic-analysis"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "BUDGET_SEMANTIC_ANALYSIS",
                            "items": [
                              {
                                "itemId": "00000000-0000-0000-0000-000000000302",
                                "enhancedDescription": "预算金额项目上下文完整，建议复核审批状态。",
                                "suggestion": "建议按当前采购方式继续办理。"
                              },
                              {
                                "itemId": "00000000-0000-0000-0000-000000000303",
                                "hint": "项目基础信息已结合规则结果完成整体分析。"
                              }
                            ],
                            "model": "qwen-test",
                            "durationMs": 18
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ProcurementAgentReviewAiPort.Result result = adapter.analyze(
                new ProcurementAgentReviewAiPort.Request(
                        projectId, "PR-2026-TEST", "办公设备采购", "信息中心", "货物",
                        "公开招标", "测试申请人", "150000.00", List.of(
                                new ProcurementAgentReviewAiPort.Item(itemId, "BUDGET_AMOUNT",
                                        "预算金额有效性", "PASSED", "INFO", "预算金额已填写且大于零",
                                        null, "金额大于 0", "150000.00"),
                                new ProcurementAgentReviewAiPort.Item(summaryId, "SUMMARY",
                                        "项目整体预审结论", "INFO", "INFO", "规则结果已生成",
                                        null, "项目基础信息完整", "办公设备采购"))));

        assertThat(result.available()).isTrue();
        assertThat(result.aiSummary()).isEqualTo("项目基础信息已结合规则结果完成整体分析。");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo(itemId);
            assertThat(item.aiExplanation()).contains("项目上下文");
            assertThat(item.aiSuggestion()).contains("当前采购方式");
        });
        assertThat(result.model()).isEqualTo("qwen-test");
        assertThat(result.durationMs()).isEqualTo(18L);
        server.verify();
    }

    @Test
    void rejectsMismatchedTypedOperationWithoutLeakingBody() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "BUDGET_SEMANTIC_ANALYSIS",
                            "summaries": [],
                            "hints": [],
                            "model": "qwen-test",
                            "durationMs": 1
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyzeContractText(
                new ContractTextAnalysisRequest(UUID.randomUUID(), List.of(
                        new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "正文")))))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("external service unavailable")
                .hasMessageNotContaining("BUDGET_SEMANTIC_ANALYSIS");
        server.verify();
    }

    @Test
    void postsTenderComplianceRequestAndMapsStructuredFindings() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID reviewId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/procurement-tender-compliance"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "projectId": "00000000-0000-0000-0000-000000000401",
                          "reviewId": "00000000-0000-0000-0000-000000000402",
                          "fullText": "必须采用某系列",
                          "segments": [{"sourceLocator":"pdf/page:1","text":"必须采用某系列","pageNumber":1}],
                          "ruleFindings": []
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "PROCUREMENT_TENDER_COMPLIANCE",
                            "model": "qwen-test",
                            "durationMs": 9,
                            "findings": [{
                              "matchedText": "必须采用某系列",
                              "sourceLocator": "pdf/page:1",
                              "pageNumber": 1,
                              "riskCategory": "技术参数倾向",
                              "riskLevel": "WARNING",
                              "rationale": "指向特定系列",
                              "remediationSuggestion": "改为功能性描述",
                              "confidence": 0.91
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ProcurementTenderReviewAiPort.Result result = adapter.analyze(
                new ProcurementTenderReviewAiPort.Request(projectId, reviewId, "必须采用某系列",
                        List.of(new ProcurementTenderReviewAiPort.Segment("pdf/page:1", "必须采用某系列", 1, 1.0)),
                        List.of()));

        assertThat(result.model()).isEqualTo("qwen-test");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.sourceLocator()).isEqualTo("pdf/page:1");
            assertThat(finding.riskLevel()).isEqualTo("WARNING");
            assertThat(finding.confidence()).isEqualTo(0.91);
        });
        server.verify();
    }

    @Test
    void preservesAllowlistedFastApiSemanticFailureCodeWithoutLeakingBody() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "MODEL_INVALID_RESULT",
                                  "message": "model returned an invalid result",
                                  "data": {"modelCalls": [{
                                    "ordinal": 1,
                                    "phase": "REPAIR",
                                    "outcome": "FAILED",
                                    "safeReason": "INVALID_PROVIDER_JSON",
                                    "durationMs": 11,
                                    "provider": "OPENAI_COMPATIBLE",
                                    "model": "qwen-test"
                                  }]},
                                  "requestId": "safe-request-id"
                                }
                                """));

        ExternalServiceException failure = catchThrowableOfType(() -> adapter.analyzeContractText(
                new ContractTextAnalysisRequest(UUID.randomUUID(), List.of(
                        new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "正文")))),
                ExternalServiceException.class);

        assertThat(failure.getCause()).isInstanceOfSatisfying(ContractTextAnalysisFailure.class,
                cause -> {
                    assertThat(cause.reason())
                            .isEqualTo(ContractTextAnalysisFailure.Reason.MODEL_INVALID_RESULT);
                    assertThat(cause.modelCalls()).singleElement().satisfies(call -> {
                        assertThat(call.phase()).isEqualTo(ContractTextAnalysisResult.CallPhase.REPAIR);
                        assertThat(call.safeReason()).isEqualTo("INVALID_PROVIDER_JSON");
                    });
                });
        assertThat(failure).hasMessageNotContaining("model returned an invalid result");
        server.verify();
    }

    @Test
    void rejectsTenderFindingWhoseMatchedTextIsAbsentFromLocatedSegment() {
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID reviewId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/procurement-tender-compliance"))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "operation": "PROCUREMENT_TENDER_COMPLIANCE",
                            "model": "qwen-test",
                            "durationMs": 9,
                            "findings": [{
                              "matchedText": "模型虚构的条款",
                              "sourceLocator": "pdf/page:1",
                              "pageNumber": 1,
                              "riskCategory": "其他",
                              "riskLevel": "INFO",
                              "rationale": "原文中不存在该片段",
                              "remediationSuggestion": "请人工复核",
                              "confidence": 0.5
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze(
                new ProcurementTenderReviewAiPort.Request(projectId, reviewId, "招标文件正文",
                        List.of(new ProcurementTenderReviewAiPort.Segment(
                                "pdf/page:1", "招标文件正文", 1, 1.0)), List.of())))
                .isInstanceOf(ProcurementTenderReviewAiPort.InvalidResponseException.class);
        server.verify();
    }

    @Test
    void rejectsGatewayFailureEnvelopeWithoutRequiredModelCallAudit() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/agent/contract-text-analysis"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "MODEL_GATEWAY_ERROR",
                                  "message": "model service is unavailable",
                                  "data": {"modelCalls": []},
                                  "requestId": "safe-request-id"
                                }
                                """));

        ExternalServiceException failure = catchThrowableOfType(() -> adapter.analyzeContractText(
                new ContractTextAnalysisRequest(UUID.randomUUID(), List.of(
                        new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "正文")))),
                ExternalServiceException.class);

        assertThat(failure.getCause()).isInstanceOfSatisfying(ContractTextAnalysisFailure.class,
                cause -> {
                    assertThat(cause.reason())
                            .isEqualTo(ContractTextAnalysisFailure.Reason.SERVICE_UNAVAILABLE);
                    assertThat(cause.modelCalls()).isEmpty();
                });
        server.verify();
    }
}
