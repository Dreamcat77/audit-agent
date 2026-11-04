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
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class BudgetSplitRiskApiIntegrationTest {

    private static final UUID PROCUREMENT_ONE =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROCUREMENT_TWO =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void financeReviewerCanReplayAndReopenDurableAssessmentWhileKeyIsActorBound() throws Exception {
        String key = UUID.randomUUID().toString();
        Authentication actor = auth("budget-f05-finance");

        String first = check(key, actor, List.of(PROCUREMENT_ONE, PROCUREMENT_TWO), 200)
                .andExpect(jsonPath("$.data.riskLevel").value("INFO"))
                .andReturn().getResponse().getContentAsString();
        String replay = check(key, actor, List.of(PROCUREMENT_TWO, PROCUREMENT_ONE), 200)
                .andReturn().getResponse().getContentAsString();

        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode replayData = objectMapper.readTree(replay).path("data");
        String assessmentId = firstData.path("assessmentId").asText();
        assertThat(assessmentId).isNotBlank();
        assertThat(replayData.path("assessmentId").asText()).isEqualTo(assessmentId);
        assertThat(replayData.path("createdAt").asText())
                .isEqualTo(firstData.path("createdAt").asText());

        mockMvc.perform(get("/api/v1/budget/split-risk/assessments/{id}", assessmentId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId));

        check(key, auth("another-finance-reviewer"),
                List.of(PROCUREMENT_ONE, PROCUREMENT_TWO), 409);
    }

    @Test
    void checkRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/budget/split-risk/check")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(PROCUREMENT_ONE, PROCUREMENT_TWO)))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions check(
            String key,
            Authentication actor,
            List<UUID> procurementIds,
            int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/budget/split-risk/check")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(procurementIds))
                        .with(authentication(actor))
                        .with(csrf()))
                .andExpect(status().is(expectedStatus));
    }

    private String body(List<UUID> procurementIds) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "procurementIds", procurementIds.stream().map(UUID::toString).toList()));
    }

    private static Authentication auth(String userId) {
        CurrentUser user = new CurrentUser(
                userId, userId, "Budget F05 Finance Reviewer", Set.of("FINANCE_REVIEWER"));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_REVIEWER")));
    }
}
