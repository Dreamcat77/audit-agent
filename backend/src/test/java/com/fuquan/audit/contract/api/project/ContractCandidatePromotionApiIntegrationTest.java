package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractCandidatePromotionApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository supports;
    @Autowired com.fuquan.audit.contract.application.project.port.ContractCandidateRepository candidates;

    @Test
    void missingAssignedSupportCohortRejectsPromotionWithCurrentConfirmationAndUnchangedRequest() throws Exception {
        var f = explicitSupportConfirmation();
        supports.deleteCurrentByProjectIdAndCandidateId(UUID.fromString(f.projectId()), UUID.fromString(f.candidateId()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        f.projectId(), f.candidateId()).contentType("application/json").content(f.promotionBody())
                        .header("Idempotency-Key", "missing-support-promote-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(f.auth())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(get("/api/v1/projects/{projectId}", f.projectId()).with(authentication(f.auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.associatedContracts").isEmpty());
        // Detail deliberately rejects a corrupt cohort; inspect the durable Candidate directly.
        assertThat(candidates.findByProjectIdAndId(UUID.fromString(f.projectId()), UUID.fromString(f.candidateId()))
                .orElseThrow().promotionState()).isEqualTo(
                        com.fuquan.audit.contract.domain.project.CandidatePromotionState.DRAFT);
    }

    @Test
    void currentExplicitSupportPromotesThroughExistingRoutePayloadResponseAndAwardMapping() throws Exception {
        var f = explicitSupportConfirmation();
        assertThat(java.util.Arrays.stream(ProjectController.PromotionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).containsExactly("expectedVersion", "confirmationId");
        var response = mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        f.projectId(), f.candidateId()).contentType("application/json").content(f.promotionBody())
                        .header("Idempotency-Key", "current-support-promote-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(f.auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_FOR_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(response, "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("contractId", "contractNo", "contractName", "status",
                "versionNo", "projectId", "candidateId");
        String contractId = data.get("contractId").toString();
        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).with(authentication(f.auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.awardNotice.noticeNo").value("HUMAN-AWARD"))
                .andExpect(jsonPath("$.data.awardNotice.winningAmount").value(100.00));
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements",
                        f.projectId(), f.candidateId()).contentType("application/json")
                        .content("{\"expectedCandidateVersion\":3,\"supportFileIds\":[]}")
                        .header("Idempotency-Key", "promoted-support-clear-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(f.auth())))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).with(authentication(f.auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.awardNotice.noticeNo").value("HUMAN-AWARD"));
    }

    private ExplicitPromotion explicitSupportConfirmation() throws Exception {
        Authentication actor = auth();
        String project = createProject(actor), candidate = uploadCandidate(project, actor);
        var uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", project)
                        .file(new MockMultipartFile("file", "award.pdf", "application/pdf", pdf("Synthetic Award Notice")))
                        .header("Idempotency-Key", "promotion-support-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String support = com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        confirmRole(project, support, actor, "AWARD_NOTICE");
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements",
                        project, candidate).contentType("application/json")
                        .content("{\"expectedCandidateVersion\":0,\"supportFileIds\":[\"%s\"]}".formatted(support))
                        .header("Idempotency-Key", "promotion-support-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateVersion").value(1));
        var confirmed = mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        project, candidate).contentType("application/json").content("""
                        {"expectedVersion":1,"contractNo":"SUPPORT-%s","contractName":"Human Contract",
                         "contractType":"GOODS","partyA":"Buyer","partyB":"Supplier","contractAmount":100.00,
                         "lineItems":[],"awardNotice":{"noticeNo":"HUMAN-AWARD","projectName":"Human Project",
                         "winningBidder":"Supplier","winningAmount":100.00,"winningDate":"2026-09-20"}}
                        """.formatted(UUID.randomUUID()))
                        .header("Idempotency-Key", "promotion-support-confirm-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultCandidateVersion").value(2)).andReturn();
        String confirmation = com.jayway.jsonpath.JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.confirmationId");
        return new ExplicitPromotion(actor, project, candidate,
                "{\"expectedVersion\":2,\"confirmationId\":\"%s\"}".formatted(confirmation));
    }

    private record ExplicitPromotion(Authentication auth, String projectId, String candidateId, String promotionBody) { }

    @Test
    void explicitConfirmationIgnoresClientOwnedIdentityAndPromotionReplaysTheDurableContract() throws Exception {
        Authentication auth = auth();
        String projectId = createProject(auth);
        String candidateId = uploadCandidate(projectId, auth);
        String clientContractId = UUID.randomUUID().toString();
        String key = "promotion-api-" + UUID.randomUUID();
        String body = """
                {
                  "expectedVersion":0,
                  "id":"%s",
                  "status":"CLIENT_CONTROLLED",
                  "versionNo":99,
                  "contractNo":"PROMOTE-API-1",
                  "contractName":"Confirmed Contract",
                  "contractType":"GOODS",
                  "partyA":"Buyer",
                  "partyB":"Supplier",
                  "partyBCreditCode":"913100000000000001",
                  "contractAmount":100.00,
                  "amountInWords":"壹佰元整",
                  "lineItems":[{
                    "id":"%s","contractId":"%s","itemKey":"ITEM-1","itemName":"Server",
                    "quantity":2,"unitPrice":50.00,"declaredAmount":100.00,"sortNo":1
                  }],
                  "awardNotice":{
                    "id":"%s","contractId":"%s","noticeNo":"AWARD-1","projectName":"Project",
                    "winningBidder":"Supplier","winningBidderCreditCode":"913100000000000001",
                    "winningAmount":100.00,"winningDate":"2026-08-20"
                  }
                }
                """.formatted(clientContractId, UUID.randomUUID(), clientContractId,
                UUID.randomUUID(), clientContractId);

        MvcResult saved = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .contentType("application/json").content(body)
                        .header("Idempotency-Key", "confirm-api-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload.contractNo").value("PROMOTE-API-1"))
                .andReturn();
        String confirmationId = com.jayway.jsonpath.JsonPath.read(
                saved.getResponse().getContentAsString(), "$.data.confirmationId");
        String promotionBody = "{\"expectedVersion\":1,\"confirmationId\":\"%s\"}"
                .formatted(confirmationId);
        MvcResult first = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .contentType("application/json").content(promotionBody)
                        .header("Idempotency-Key", key).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").isNotEmpty())
                .andExpect(jsonPath("$.data.contractId").value(org.hamcrest.Matchers.not(clientContractId)))
                .andExpect(jsonPath("$.data.status").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.data.versionNo").value(0))
                .andReturn();
        String contractId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.contractId");

        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .contentType("application/json").content(promotionBody)
                        .header("Idempotency-Key", key).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contractId").value(contractId));
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.promotionState").value("PROMOTED"))
                .andExpect(jsonPath("$.data.candidate.promotedContractId").value(contractId))
                .andExpect(jsonPath("$.data.candidate.promotedConfirmationId").value(confirmationId));
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associatedContracts[0].contractId").value(contractId));

        MvcResult review = mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", contractId)
                        .header("Idempotency-Key", "promoted-direct-f02-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").isNotEmpty())
                .andExpect(jsonPath("$.data.findings.length()").value(5))
                .andExpect(jsonPath("$.data.findings[?(@.checkStatus != 'PASS')]").isEmpty())
                .andReturn();
        String taskId = com.jayway.jsonpath.JsonPath.read(
                review.getResponse().getContentAsString(), "$.data.taskId");
        mockMvc.perform(get("/api/v1/contracts/{contractId}/audit-tasks/{taskId}/review-progress",
                        contractId, taskId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.source.available == true)]").isNotEmpty());
        mockMvc.perform(get("/api/v1/contracts/{id}/review-sessions", contractId)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void promotionRequiresAuthenticationAndCsrf() throws Exception {
        String route = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote";
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .contentType("application/json").content("{}").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .contentType("application/json").content("{}")
                        .header("Idempotency-Key", "missing-csrf").with(authentication(auth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void promotionInputBoundaryRejectsUnsafeKeysAndNegativeVersionBeforeAnyWrite() throws Exception {
        Authentication auth = auth();
        String projectId = createProject(auth);
        String candidateId = uploadCandidate(projectId, auth);
        String route = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote";

        mockMvc.perform(post(route, projectId, candidateId)
                        .contentType("application/json").content(validPromotionBody(0))
                        .header("Idempotency-Key", "x".repeat(129)).with(csrf()).with(authentication(auth)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(route, projectId, candidateId)
                        .contentType("application/json").content(validPromotionBody(0))
                        .header("Idempotency-Key", "unsafe key").with(csrf()).with(authentication(auth)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(route, projectId, candidateId)
                        .contentType("application/json").content(validPromotionBody(-1))
                        .header("Idempotency-Key", "safe-key").with(csrf()).with(authentication(auth)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.promotionState").value("DRAFT"));
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associatedContracts").isEmpty());
    }

    @Test
    void pm44BaselineDocxRowsBecomeThreeDurableLinesAndAllFiveF02CategoriesPass() throws Exception {
        Authentication auth = auth();
        String projectId = createProject(auth);
        String candidateId = uploadDocxCandidate(projectId, auth, "pm44-01-contract.docx", lineItemDocx(
                new String[][]{
                        {"SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00"},
                        {"SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00"},
                        {"TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00"}
                }));
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.lineItems.length()").value(3))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].itemKey").value("SOFT-01"))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].quantity").value(1))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].unitPrice").value(40000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].declaredAmount").value(40000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].itemKey").value("SERV-01"))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].declaredAmount").value(17000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[2].itemKey").value("TRAIN-01"));
        String body = """
                {
                  "expectedVersion":0,
                  "contractNo":"HT-PM44-2026-001",
                  "contractName":"智慧维修系统采购项目政府采购合同",
                  "contractType":"SERVICES",
                  "partyA":"某市政务服务中心（测试）",
                  "partyB":"华辰数字科技有限公司（测试）",
                  "partyBCreditCode":"91310000TEST440001X",
                  "contractAmount":67000.00,
                  "amountInWords":"人民币陆万柒仟元整",
                  "lineItems":[
                    {"itemKey":"SOFT-01","itemName":"智慧维修系统软件许可","quantity":1,"unitPrice":40000.00,"declaredAmount":40000.00,"sortNo":0},
                    {"itemKey":"SERV-01","itemName":"部署实施与数据初始化","quantity":1,"unitPrice":17000.00,"declaredAmount":17000.00,"sortNo":1},
                    {"itemKey":"TRAIN-01","itemName":"培训与接口对接服务","quantity":1,"unitPrice":10000.00,"declaredAmount":10000.00,"sortNo":2}
                  ],
                  "awardNotice":{
                    "noticeNo":"AWARD-PM44-001","projectName":"智慧维修系统采购项目",
                    "winningBidder":"华辰数字科技有限公司（测试）",
                    "winningBidderCreditCode":"91310000TEST440001X","winningAmount":67000.00,
                    "winningDate":"2026-05-20"
                  }
                }
                """;

        String contractId = promote(projectId, candidateId, body, auth);

        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineItems.length()").value(3))
                .andExpect(jsonPath("$.data.lineItems[0].itemKey").value("SOFT-01"))
                .andExpect(jsonPath("$.data.lineItems[0].quantity").value(1))
                .andExpect(jsonPath("$.data.lineItems[0].unitPrice").value(40000.00))
                .andExpect(jsonPath("$.data.lineItems[0].declaredAmount").value(40000.00))
                .andExpect(jsonPath("$.data.lineItems[1].declaredAmount").value(17000.00))
                .andExpect(jsonPath("$.data.lineItems[2].declaredAmount").value(10000.00));
        List<Map<String, Object>> findings = review(contractId, auth);
        assertThat(findings).extracting(finding -> finding.get("ruleCode"))
                .containsAll(F02RuleCodes.ALL);
        assertThat(findings).allSatisfy(finding -> assertThat(finding.get("checkStatus")).isEqualTo("PASS"));
    }

    @Test
    void amountOnlyConfirmationPromotesNullQuantityPriceAndF02StillEvaluatesDeclaredSum() throws Exception {
        Authentication auth = auth();
        String projectId = createProject(auth);
        String candidateId = uploadCandidate(projectId, auth);
        String body = """
                {
                  "expectedVersion":0,
                  "contractNo":"AMOUNT-ONLY-%s",
                  "contractName":"服务金额分项合同",
                  "contractType":"SERVICES",
                  "partyA":"Buyer",
                  "partyB":"Supplier",
                  "contractAmount":67000.00,
                  "amountInWords":"人民币陆万柒仟元整",
                  "lineItems":[
                    {"itemKey":"S-1","itemName":"服务一","basis":"AMOUNT_ONLY","declaredAmount":28000.00,"sortNo":0},
                    {"itemKey":"S-2","itemName":"服务二","basis":"AMOUNT_ONLY","declaredAmount":16000.00,"sortNo":1},
                    {"itemKey":"S-3","itemName":"服务三","basis":"AMOUNT_ONLY","declaredAmount":9000.00,"sortNo":2},
                    {"itemKey":"S-4","itemName":"服务四","basis":"AMOUNT_ONLY","declaredAmount":14000.00,"sortNo":3}
                  ]
                }
                """.formatted(UUID.randomUUID());

        String contractId = promote(projectId, candidateId, body, auth);

        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineItems.length()").value(4))
                .andExpect(jsonPath("$.data.lineItems[0].basis").value("AMOUNT_ONLY"))
                .andExpect(jsonPath("$.data.lineItems[0].quantity").doesNotExist())
                .andExpect(jsonPath("$.data.lineItems[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.data.lineItems[0].declaredAmount").value(28000.00));
        List<Map<String, Object>> findings = review(contractId, auth);
        assertThat(findings.stream().filter(value -> F02RuleCodes.LINE_ITEM_ARITHMETIC
                .equals(value.get("ruleCode"))).toList()).hasSize(4)
                .allSatisfy(value -> assertThat(value.get("checkStatus")).isEqualTo("NOT_EVALUATED"));
        assertThat(findings.stream().filter(value -> F02RuleCodes.LINE_SUM_MATCH
                .equals(value.get("ruleCode"))).toList()).singleElement()
                .satisfies(value -> assertThat(value.get("checkStatus")).isEqualTo("PASS"));
    }

    @Test
    void pm44FiveRiskShapeKeepsEveryF02CategoryNonPassing() throws Exception {
        Authentication auth = auth();
        String projectId = createProject(auth);
        String candidateId = uploadDocxCandidate(projectId, auth, "pm44-05-contract.docx", lineItemDocx(
                new String[][]{
                        {"DESKTOP-01", "台式计算机", "100", "5000.00", "490000.00"},
                        {"LAPTOP-01", "便携式计算机", "20", "7000.00", "140000.00"}
                }));
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.lineItems.length()").value(2))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].itemKey").value("DESKTOP-01"))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].quantity").value(100))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].unitPrice").value(5000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].declaredAmount").value(490000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].itemKey").value("LAPTOP-01"))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].quantity").value(20))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].unitPrice").value(7000.00))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].declaredAmount").value(140000.00));
        String body = """
                {
                  "expectedVersion":0,
                  "contractNo":"HT-PM44-2026-005",
                  "contractName":"数据中心办公设备采购项目政府采购合同",
                  "contractType":"GOODS",
                  "partyA":"某市政务服务中心（测试）",
                  "partyB":"华辰数字科技有限公司（测试）",
                  "partyBCreditCode":"91310000TEST440005X",
                  "contractAmount":407000.00,
                  "amountInWords":"人民币肆拾万元整",
                  "lineItems":[
                    {"itemKey":"DESKTOP-01","itemName":"台式计算机","quantity":100,"unitPrice":5000.00,"declaredAmount":490000.00,"sortNo":0},
                    {"itemKey":"LAPTOP-01","itemName":"便携式计算机","quantity":20,"unitPrice":7000.00,"declaredAmount":140000.00,"sortNo":1}
                  ],
                  "awardNotice":{
                    "noticeNo":"AWARD-PM44-005","projectName":"数据中心办公设备采购项目",
                    "winningBidder":"华辰数智科技有限公司（测试）",
                    "winningBidderCreditCode":"91310000TEST440099X","winningAmount":395000.00,
                    "winningDate":"2026-05-20"
                  }
                }
                """;

        String contractId = promote(projectId, candidateId, body, auth);
        List<Map<String, Object>> findings = review(contractId, auth);

        Set<Object> nonPassingRuleCodes = findings.stream()
                .filter(finding -> !"PASS".equals(finding.get("checkStatus")))
                .map(finding -> finding.get("ruleCode"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(nonPassingRuleCodes).containsExactlyInAnyOrderElementsOf(F02RuleCodes.ALL);
    }

    private String createProject(Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "promotion-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "promotion-project-metadata-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"PM44 代码级回归项目\","
                                + "\"projectNo\":\"SYN-PM44-2026\"}")
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());
        return projectId;
    }

    private String uploadCandidate(String projectId, Authentication auth) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", pdf(
                "Contract No: EXTRACTED-1\nContract Name: Extracted Contract\nContract Amount: 100.00\n"
                        + "Line Item [ITEM-1] Name: Server Quantity: 2 Unit Price: 50.00 Declared Amount: 100.00"));
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "promotion-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String fileId = com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(), "$.data.id");
        return confirmContractRole(projectId, fileId, auth);
    }

    private String uploadDocxCandidate(String projectId, Authentication auth, String filename, byte[] bytes)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "promotion-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String fileId = com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(), "$.data.id");
        return confirmContractRole(projectId, fileId, auth);
    }

    private String confirmContractRole(String projectId, String fileId, Authentication auth) throws Exception {
        return confirmRole(projectId, fileId, auth, "CONTRACT_BODY");
    }

    private String confirmRole(String projectId, String fileId, Authentication auth, String role) throws Exception {
        MvcResult identityAssessment = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "promotion-identity-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String identityAssessmentId = com.jayway.jsonpath.JsonPath.read(
                identityAssessment.getResponse().getContentAsString(), "$.data.assessmentId");
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", "promotion-identity-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                                + "\"expectedVersion\":0,\"reason\":\"synthetic ownership\"}")
                                .formatted(identityAssessmentId))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());
        MvcResult assessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "promotion-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = com.jayway.jsonpath.JsonPath.read(
                assessed.getResponse().getContentAsString(), "$.data.assessmentId");
        String body = "{\"assessmentId\":\"%s\",\"selectedRole\":\"%s\","
                + "\"expectedVersion\":0,\"reason\":\"synthetic promotion fixture\"}";
        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .contentType("application/json").content(body.formatted(assessmentId, role))
                        .header("Idempotency-Key", "promotion-confirm-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andReturn();
        if (!role.equals("CONTRACT_BODY")) return fileId;
        assertThat((String) com.jayway.jsonpath.JsonPath.read(confirmed.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isEqualTo("MATERIALIZED");
        return com.jayway.jsonpath.JsonPath.read(
                confirmed.getResponse().getContentAsString(), "$.data.candidateId");
    }

    private String promote(String projectId, String candidateId, String body, Authentication auth) throws Exception {
        MvcResult saved = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .contentType("application/json").content(body)
                        .header("Idempotency-Key", "pm44-confirm-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String confirmationId = com.jayway.jsonpath.JsonPath.read(
                saved.getResponse().getContentAsString(), "$.data.confirmationId");
        long resultVersion = ((Number) com.jayway.jsonpath.JsonPath.read(
                saved.getResponse().getContentAsString(), "$.data.resultCandidateVersion")).longValue();
        String promotionBody = "{\"expectedVersion\":%d,\"confirmationId\":\"%s\"}"
                .formatted(resultVersion, confirmationId);
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .contentType("application/json").content(promotionBody)
                        .header("Idempotency-Key", "pm44-promote-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.contractId");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> review(String contractId, Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", contractId)
                        .header("Idempotency-Key", "pm44-review-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.findings");
    }

    private static Authentication auth() {
        CurrentUser user = new CurrentUser("promotion-user", "promotion.user", "Promotion User", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }

    private static String validPromotionBody(long expectedVersion) {
        return "{\"expectedVersion\":%d,\"confirmationId\":\"%s\"}"
                .formatted(expectedVersion, UUID.randomUUID());
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700);
                for (String line : text.split("\\n")) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] lineItemDocx(String[][] lineItems) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("项目名称：PM44 代码级回归项目");
            XWPFTable table = document.createTable(lineItems.length + 1, 5);
            String[] headers = {"标识", "货物/服务名称", "数量", "单价（元）", "申报金额（元）"};
            for (int cell = 0; cell < headers.length; cell++) {
                table.getRow(0).getCell(cell).setText(headers[cell]);
            }
            for (int row = 0; row < lineItems.length; row++) {
                for (int cell = 0; cell < lineItems[row].length; cell++) {
                    table.getRow(row + 1).getCell(cell).setText(lineItems[row][cell]);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }
}
