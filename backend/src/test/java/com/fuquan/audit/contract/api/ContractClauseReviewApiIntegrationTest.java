package com.fuquan.audit.contract.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.contract.application.clause.BreachClauseReview;
import com.fuquan.audit.contract.application.clause.BreachClauseReviewUseCase;
import com.fuquan.audit.contract.application.clause.ContractTermClauseReview;
import com.fuquan.audit.contract.application.clause.ContractTermClauseReviewUseCase;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractClauseReviewApiIntegrationTest {
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

    @Autowired private MockMvc mockMvc;
    @MockBean private BreachClauseReviewUseCase breach;
    @MockBean private ContractTermClauseReviewUseCase term;

    @Test
    void exposesTwoIndependentBusinessUseCasesAndKeepsRoleSecurity() throws Exception {
        when(breach.review(PROJECT_ID, CONTRACT_ID, List.of(FILE_ID))).thenReturn(new BreachClauseReview(List.of(
                new BreachClauseReview.Item("逾期交付", new BigDecimal("1"), "PERCENT", "WEEK",
                        "CONTRACT_AMOUNT", new BigDecimal("670.00"), null, null, null, null,
                        "延期超过一个月可解除", "REVIEW", "累计违约金上限未明确，建议复核",
                        FILE_ID, "每延期一周按合同金额1%支付违约金")), false));
        when(term.review(PROJECT_ID, CONTRACT_ID, List.of(FILE_ID))).thenReturn(new ContractTermClauseReview(List.of(
                new ContractTermClauseReview.DateItem("DELIVERY_DEADLINE", "2026年6月30日",
                        "2026-06-30", "NORMAL", FILE_ID, "交付截止2026年6月30日", true)),
                List.of(new ContractTermClauseReview.ConditionItem("EFFECTIVE_EVENT",
                        "双方签字、盖章后生效", "NORMAL", FILE_ID, "双方签字、盖章后生效", true)), false));

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/clause-reviews/breach",
                        PROJECT_ID, CONTRACT_ID).with(csrf()).with(authentication(auditor()))
                        .contentType("application/json").content("{\"fileIds\":[\"" + FILE_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].correspondingAmount").value(670.00))
                .andExpect(jsonPath("$.data.items[0].actualPenaltyAmount").isEmpty());
        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/clause-reviews/term-termination",
                        PROJECT_ID, CONTRACT_ID).with(csrf()).with(authentication(auditor()))
                        .contentType("application/json").content("{\"fileIds\":[\"" + FILE_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dates[0].normalizedDate").value("2026-06-30"))
                .andExpect(jsonPath("$.data.dates[0].locatorAvailable").value(true))
                .andExpect(jsonPath("$.data.conditions[0].type").value("EFFECTIVE_EVENT"))
                .andExpect(jsonPath("$.data.conditions[0].locatorAvailable").value(true));
        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/clause-reviews/breach",
                        PROJECT_ID, CONTRACT_ID).with(csrf())).andExpect(status().isUnauthorized());
        verify(breach).review(PROJECT_ID, CONTRACT_ID, List.of(FILE_ID));
        verify(term).review(PROJECT_ID, CONTRACT_ID, List.of(FILE_ID));
    }

    @Test
    void rejectsMissingEmptyDuplicateAndOversizedMaterialSelections() throws Exception {
        String duplicate = "{\"fileIds\":[\"" + FILE_ID + "\",\"" + FILE_ID + "\"]}";
        String oversized = "{\"fileIds\":[" + java.util.stream.IntStream.range(0, 51)
                .mapToObj(ignored -> "\"" + UUID.randomUUID() + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
        for (String body : List.of("{}", "{\"fileIds\":[]}", duplicate, oversized)) {
            mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/clause-reviews/breach",
                            PROJECT_ID, CONTRACT_ID).with(csrf()).with(authentication(auditor()))
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    private UsernamePasswordAuthenticationToken auditor() {
        CurrentUser user = new CurrentUser("clause-user", "clause.user", "条款审核员", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }
}
