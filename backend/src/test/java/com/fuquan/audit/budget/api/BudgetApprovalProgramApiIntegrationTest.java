package com.fuquan.audit.budget.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BudgetApprovalProgramApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BudgetReviewTaskRepository taskRepository;
    @Autowired private BudgetReviewItemRepository itemRepository;
    @Autowired private InMemoryOperationAuditAdapter auditAdapter;

    private UUID reviewedTaskId;
    private UUID reviewedItemId;

    @BeforeEach
    void createReviewedF01Source() {
        reviewedTaskId = UUID.randomUUID();
        reviewedItemId = UUID.randomUUID();
        taskRepository.save(task(reviewedTaskId, "REVIEWED"));
        itemRepository.saveAll(List.of(item(
                reviewedItemId, reviewedTaskId, new BigDecimal("50000.00"))));
    }

    @Test
    void exposesActiveRuleMatrixWithoutFrontendBoundaryInference() throws Exception {
        mockMvc.perform(get("/api/v1/budget/approval-program/rules")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleSetCode").value("PM_DEMO_20260828"))
                .andExpect(jsonPath("$.data.ruleGroups.length()").value(2))
                .andExpect(jsonPath("$.data.ruleGroups[0].code").value("PROCEDURE_REQUIREMENT"))
                .andExpect(jsonPath("$.data.ruleGroups[0].rules[0].stepCode").value("REQUEST_APPROVAL"))
                .andExpect(jsonPath("$.data.ruleGroups[0].rules[0].stepName").value("请示报批"))
                .andExpect(jsonPath("$.data.ruleGroups[0].rules[0].thresholdLabel").isNotEmpty())
                .andExpect(jsonPath("$.data.ruleGroups[1].code").value("AUTHORITY_LEVEL"))
                .andExpect(jsonPath("$.data.ruleGroups[1].rules.length()").value(3));
    }

    @Test
    void validatesAgainstDurableF01AmountAndStoresAuthenticatedAudit() throws Exception {
        String response = postValidation(
                UUID.randomUUID().toString(), reviewedTaskId, reviewedItemId,
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"), 200)
                .andExpect(jsonPath("$.data.ruleSetCode").value("PM_DEMO_20260828"))
                .andExpect(jsonPath("$.data.taskId").value(reviewedTaskId.toString()))
                .andExpect(jsonPath("$.data.itemId").value(reviewedItemId.toString()))
                .andExpect(jsonPath("$.data.sourceRowNumber").value(2))
                .andExpect(jsonPath("$.data.declaredAmount").value(50000.00))
                .andExpect(jsonPath("$.data.requiredProcedureSteps[0].displayName").value("请示报批"))
                .andExpect(jsonPath("$.data.requiredAuthorityStep.code").value("FINANCE_REVIEW"))
                .andExpect(jsonPath("$.data.actualSource").value("MANUAL_TEST"))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andReturn().getResponse().getContentAsString();

        String validationId = objectMapper.readTree(response).path("data").path("validationId").asText();
        assertThat(validationId).isNotBlank();
        assertThat(auditAdapter.snapshot()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("BUDGET_APPROVAL_PROGRAM_VALIDATED");
            assertThat(event.userId()).isEqualTo("budget-f04-test-user");
            assertThat(event.resourceId()).isEqualTo(validationId);
        });
    }

    @Test
    void rejectsInvalidActualStepsAndF01SourceStates() throws Exception {
        postValidation(UUID.randomUUID().toString(), reviewedTaskId, reviewedItemId,
                List.of("FINANCE_REVIEW", "FINANCE_REVIEW"), 400);
        postValidation(UUID.randomUUID().toString(), reviewedTaskId, reviewedItemId,
                List.of("UNKNOWN_STEP"), 400);

        UUID parsedTask = UUID.randomUUID();
        UUID parsedItem = UUID.randomUUID();
        taskRepository.save(task(parsedTask, "PARSED"));
        itemRepository.saveAll(List.of(item(parsedItem, parsedTask, BigDecimal.TEN)));
        postValidation(UUID.randomUUID().toString(), parsedTask, parsedItem,
                List.of("FINANCE_REVIEW"), 409);

        UUID otherTask = UUID.randomUUID();
        taskRepository.save(task(otherTask, "REVIEWED"));
        postValidation(UUID.randomUUID().toString(), otherTask, reviewedItemId,
                List.of("FINANCE_REVIEW"), 404);

        UUID nullAmountItem = UUID.randomUUID();
        itemRepository.saveAll(List.of(item(nullAmountItem, reviewedTaskId, null)));
        postValidation(UUID.randomUUID().toString(), reviewedTaskId, nullAmountItem,
                List.of("FINANCE_REVIEW"), 422);
    }

    @Test
    void replaysSameFingerprintRejectsDifferentFingerprintAndListsRealHistory() throws Exception {
        String key = UUID.randomUUID().toString();
        List<String> actual = List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW");
        String first = postValidation(key, reviewedTaskId, reviewedItemId, actual, 200)
                .andReturn().getResponse().getContentAsString();
        String replay = postValidation(key, reviewedTaskId, reviewedItemId, actual, 200)
                .andReturn().getResponse().getContentAsString();

        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode replayData = objectMapper.readTree(replay).path("data");
        assertThat(replayData.path("validationId").asText())
                .isEqualTo(firstData.path("validationId").asText());
        assertThat(replayData.path("createdAt").asText())
                .isEqualTo(firstData.path("createdAt").asText());

        postValidation(key, reviewedTaskId, reviewedItemId,
                List.of("FINANCE_REVIEW"), 409);

        mockMvc.perform(get("/api/v1/budget/approval-program/validations")
                        .param("taskId", reviewedTaskId.toString())
                        .param("page", "1")
                        .param("size", "20")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].validationId")
                        .value(firstData.path("validationId").asText()))
                .andExpect(jsonPath("$.data.items[0].actualSource").value("MANUAL_TEST"));
    }

    private org.springframework.test.web.servlet.ResultActions postValidation(
            String key,
            UUID taskId,
            UUID itemId,
            List<String> steps,
            int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "taskId", taskId.toString(),
                "itemId", itemId.toString(),
                "actualSteps", steps));
        return mockMvc.perform(post("/api/v1/budget/approval-program/validations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(auth()))
                        .with(csrf()))
                .andExpect(status().is(expectedStatus));
    }

    private static BudgetReviewTask task(UUID id, String status) {
        Instant now = Instant.parse("2026-08-28T10:00:00Z");
        return new BudgetReviewTask(
                id, "budget-f04.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                100L, null, status, 1, null, "F04_TEST", "F04_TEST", now, now);
    }

    private static BudgetReviewItem item(UUID id, UUID taskId, BigDecimal amount) {
        return new BudgetReviewItem(
                id, taskId, 2, "办公设备采购", amount,
                "办公设备采购", amount, "MATCHED", "EXACT", 0,
                "PASSED", BigDecimal.ZERO, null, null,
                Instant.parse("2026-08-28T10:00:00Z"));
    }

    private static Authentication auth() {
        CurrentUser user = new CurrentUser(
                "budget-f04-test-user", "budget.f04", "Budget F04 Test", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }
}
