package com.fuquan.audit.contract.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ContractSummary;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties =
        "logging.level.com.fuquan.audit.common.observability.FlowDebugLogger=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class ContractReviewApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryOperationAuditAdapter operationAuditAdapter;

    @Autowired
    private InMemoryIdempotencyAdapter idempotencyAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousContractRequestIs401() throws Exception {
        mockMvc.perform(get("/api/v1/contracts/{id}", LocalContractFixtures.PASS_CONTRACT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserWithoutAllowedRoleIs403() throws Exception {
        mockMvc.perform(get("/api/v1/contracts/{id}", LocalContractFixtures.PASS_CONTRACT_ID)
                        .with(authentication(authForRole("VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void allowedRolesCanReadContract() throws Exception {
        for (String role : List.of("PROCUREMENT_MANAGER", "AUDITOR", "LEGAL")) {
            mockMvc.perform(get("/api/v1/contracts/{id}", LocalContractFixtures.PASS_CONTRACT_ID)
                            .with(authentication(authForRole(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contractNo").value("LOCAL-PASS-001"));
        }
    }

    @Test
    void existingContractMoneyApiRemainsNumericAtTheDecimal18Scale2Boundary() throws Exception {
        UUID contractId = UUID.fromString("00000000-0000-0000-0000-000000009999");
        BigDecimal maximum = new BigDecimal("9999999999999999.99");
        Money maximumMoney = Money.of(maximum);
        Instant timestamp = Instant.parse("2026-08-25T00:00:00Z");
        Contract contract = new Contract(
                contractId, "MAX-DECIMAL", "Exact decimal contract", "PROCUREMENT",
                "Buyer", "Supplier", "91310000EXACT0001", maximumMoney,
                "  人民币玖仟玖佰玖拾玖万亿元整  ", "READY_FOR_REVIEW", 1,
                timestamp, timestamp,
                List.of(new ContractLineItem(
                        UUID.fromString("00000000-0000-0000-0000-000000009998"), contractId,
                        "ITEM-1", "Exact item", BigDecimal.ONE, maximum, maximumMoney, 1)),
                new AwardNoticeReference(
                        UUID.fromString("00000000-0000-0000-0000-000000009997"), contractId,
                        "AWARD-MAX", "Exact project", "Supplier", "91310000EXACT0001",
                        maximumMoney, LocalDate.of(2026, 8, 25), "exact-source"));

        String json = objectMapper.writeValueAsString(
                ApiResponse.success(ContractController.ContractResponse.from(contract)));
        JsonNode data = objectMapper.readTree(json).path("data");

        assertNumericDecimal(data.path("contractAmount"));
        assertNumericDecimal(data.path("lineItems").path(0).path("unitPrice"));
        assertNumericDecimal(data.path("lineItems").path(0).path("declaredAmount"));
        assertNumericDecimal(data.path("awardNotice").path("winningAmount"));
        assertThat(data.path("amountInWords").textValue())
                .isEqualTo("  人民币玖仟玖佰玖拾玖万亿元整  ");

        ContractSummary summary = new ContractSummary(
                contractId, "MAX-DECIMAL", "Exact decimal contract", "PROCUREMENT",
                "Buyer", "Supplier", "91310000EXACT0001", maximum,
                contract.amountInWords(), contract.status(), contract.versionNo(), timestamp, timestamp);
        JsonNode summaryData = objectMapper.readTree(objectMapper.writeValueAsString(
                ApiResponse.success(ContractController.ContractSummaryResponse.from(summary))))
                .path("data");
        assertNumericDecimal(summaryData.path("contractAmount"));
    }

    private static void assertNumericDecimal(JsonNode node) {
        assertThat(node.isNumber()).isTrue();
    }

    @Test
    void missingCsrfRejectsReviewBeforeBusinessExecution() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", LocalContractFixtures.PASS_CONTRACT_ID)
                        .header("Idempotency-Key", "csrf-missing-" + UUID.randomUUID())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void missingIdempotencyHeaderIs400() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", LocalContractFixtures.PASS_CONTRACT_ID)
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void missingContractIs404() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", UUID.randomUUID())
                        .header("Idempotency-Key", "missing-contract-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void redReviewIsHttp200AndResultsDeviationsAndChecklistAreProjections(CapturedOutput output)
            throws Exception {
        String key = "fail-review-" + UUID.randomUUID();
        String requestId = "f02-api-" + UUID.randomUUID();

        MvcResult review = mockMvc.perform(post(
                                "/api/v1/contracts/{id}/agent-review", LocalContractFixtures.FAIL_CONTRACT_ID)
                        .header("Idempotency-Key", key)
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.overallRisk").value("RED"))
                .andExpect(jsonPath("$.data.blockSign").value(true))
                .andExpect(jsonPath("$.data.findings[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.findings[?(@.ruleCode == 'F02_AWARD_AMOUNT_MATCH')].checkStatus")
                        .value(org.hamcrest.Matchers.hasItem("FAIL")))
                .andReturn();
        String taskId = com.jayway.jsonpath.JsonPath.read(
                review.getResponse().getContentAsString(), "$.data.taskId");

        mockMvc.perform(get(
                                "/api/v1/contracts/{id}/agent-results", LocalContractFixtures.FAIL_CONTRACT_ID)
                        .with(authentication(authForRole("LEGAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.findings.length()").value(5))
                .andExpect(jsonPath("$.data.findings[0].createdAt").isNotEmpty());
        mockMvc.perform(get(
                                "/api/v1/contracts/{id}/deviations", LocalContractFixtures.FAIL_CONTRACT_ID)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviations.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.deviations[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.deviations[?(@.checkStatus == 'PASS')]").isEmpty());
        mockMvc.perform(get(
                                "/api/v1/contracts/{id}/sign-checklist", LocalContractFixtures.FAIL_CONTRACT_ID)
                        .with(authentication(authForRole("PROCUREMENT_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallRisk").value("RED"))
                .andExpect(jsonPath("$.data.blockSign").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(5));

        List<OperationAuditEvent> correlated = operationAuditAdapter.snapshot().stream()
                .filter(event -> requestId.equals(event.requestId()))
                .toList();
        assertThat(correlated).extracting(OperationAuditEvent::eventType)
                .containsExactly("CONTRACT_REVIEW_STARTED", "CONTRACT_REVIEW_COMPLETED");
        assertThat(correlated).allSatisfy(event -> {
            assertThat(event.userId()).isEqualTo("contract-test-user");
            assertThat(event.resourceId()).isEqualTo(LocalContractFixtures.FAIL_CONTRACT_ID.toString());
            assertThat(event.taskId()).isEqualTo(taskId);
            assertThat(event.traceId()).matches("[0-9a-f]{32}");
            assertThat(event.detail() == null ? "" : event.detail())
                    .doesNotContainIgnoringCase("Local failing contract");
        });
        assertThat(output).contains("RULE_START").contains("RULE_END")
                .doesNotContain("Different Synthetic Supplier");
    }

    @Test
    void contractOwnedReviewProgressProjectsRealFactsAndPerformsNoReadSideWrites() throws Exception {
        MvcResult review = review(
                LocalContractFixtures.FAIL_CONTRACT_ID,
                "progress-review-" + UUID.randomUUID());
        String reviewJson = review.getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(reviewJson, "$.data.taskId");
        List<String> riskResultIds = com.jayway.jsonpath.JsonPath.read(
                reviewJson, "$.data.findings[?(@.checkStatus == 'FAIL')].id");
        String acknowledgedResultId = riskResultIds.get(0);

        mockMvc.perform(post(
                                "/api/v1/audit-check-results/{checkResultId}/acknowledgement",
                                acknowledgedResultId)
                        .header("Idempotency-Key", "progress-ack-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkResultId").value(acknowledgedResultId));
        int auditCountBeforeReads = operationAuditAdapter.snapshot().size();
        int idempotencyCountBeforeReads = idempotencyAdapter.size();

        mockMvc.perform(get(
                                "/api/v1/contracts/{contractId}/audit-tasks/{taskId}/review-progress",
                                LocalContractFixtures.FAIL_CONTRACT_ID,
                                taskId)
                        .with(authentication(authForRole("LEGAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items.length()").value(6))
                .andExpect(jsonPath("$.data.riskSummary").value("低风险"))
                .andExpect(jsonPath("$.data.items[0].type").value("SOURCE_PREPARATION"))
                .andExpect(jsonPath("$.data.items[0].checkResultId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].title").value("读取合同及比对来源"))
                .andExpect(jsonPath("$.data.items[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data.items[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.items[0].risk").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].acknowledgement").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].type").value("FINDING"))
                .andExpect(jsonPath("$.data.items[1].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.checkResultId == '"
                                + acknowledgedResultId
                                + "')].acknowledgement.checkResultId")
                        .value(hasItem(acknowledgedResultId)));

        mockMvc.perform(get(
                                "/api/v1/contracts/{contractId}/audit-tasks/{taskId}/review-progress",
                                LocalContractFixtures.PASS_CONTRACT_ID,
                                taskId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/v1/audit-tasks/{taskId}/review-progress", taskId)
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        assertThat(operationAuditAdapter.snapshot()).hasSize(auditCountBeforeReads);
        assertThat(idempotencyAdapter.size()).isEqualTo(idempotencyCountBeforeReads);
    }

    @Test
    void succeededDuplicateReplaysSameTaskWithoutSecondExecution() throws Exception {
        String key = "replay-" + UUID.randomUUID();
        int recordsBefore = idempotencyAdapter.size();
        MvcResult first = review(LocalContractFixtures.PASS_CONTRACT_ID, key);
        MvcResult second = review(LocalContractFixtures.PASS_CONTRACT_ID, key);

        String firstTask = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.taskId");
        String secondTask = com.jayway.jsonpath.JsonPath.read(
                second.getResponse().getContentAsString(), "$.data.taskId");
        Boolean replayed = com.jayway.jsonpath.JsonPath.read(
                second.getResponse().getContentAsString(), "$.data.replayed");

        assertThat(secondTask).isEqualTo(firstTask);
        assertThat(replayed).isTrue();
        assertThat(idempotencyAdapter.size()).isEqualTo(recordsBefore + 1);
    }

    @Test
    void sameKeyWithDifferentContractFingerprintIs409() throws Exception {
        String key = "fingerprint-conflict-" + UUID.randomUUID();
        review(LocalContractFixtures.PASS_CONTRACT_ID, key);

        mockMvc.perform(post(
                                "/api/v1/contracts/{id}/agent-review", LocalContractFixtures.FAIL_CONTRACT_ID)
                        .header("Idempotency-Key", key)
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    private MvcResult review(UUID contractId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/contracts/{id}/agent-review", contractId)
                        .header("Idempotency-Key", key)
                        .with(csrf())
                        .with(authentication(authForRole("AUDITOR"))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser(
                "contract-test-user", "contract.test", "Contract Test", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
