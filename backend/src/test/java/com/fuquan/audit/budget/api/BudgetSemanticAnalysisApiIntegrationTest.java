package com.fuquan.audit.budget.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult.Item;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisUseCase;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BudgetSemanticAnalysisApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BudgetSemanticAnalysisUseCase useCase;

    @Test
    void authenticatedBudgetAnalysisReturnsOnlySemanticProvenance() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(useCase.analyze(taskId)).thenReturn(new BudgetSemanticAnalysisResult(
                true,
                List.of(new Item(itemId, "办公设备", "描述包含打印设备")),
                "qwen-test",
                5L));

        mockMvc.perform(post("/api/v1/budget/review-tasks/{taskId}/ai/semantic-analysis", taskId)
                        .header("X-Request-Id", "budget-ai-api-request")
                        .with(authentication(auth("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "budget-ai-api-request"))
                .andExpect(jsonPath("$.data.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.data.operation").value("BUDGET_SEMANTIC_ANALYSIS"))
                .andExpect(jsonPath("$.data.requestId").value("budget-ai-api-request"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.items[0].itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.data.items[0].suggestedCategory").value("办公设备"))
                .andExpect(jsonPath("$.data.items[0].amount").doesNotExist())
                .andExpect(jsonPath("$.data.amount").doesNotExist())
                .andExpect(jsonPath("$.data.model").value("qwen-test"))
                .andExpect(jsonPath("$.data.durationMs").value(5));
    }

    @Test
    void budgetAnalysisRequiresAuthenticationAndCsrf() throws Exception {
        UUID taskId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/budget/review-tasks/{taskId}/ai/semantic-analysis", taskId)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/budget/review-tasks/{taskId}/ai/semantic-analysis", taskId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    private static Authentication auth(String role) {
        CurrentUser user = new CurrentUser("budget-ai-user", "budget.ai", "Budget AI", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
