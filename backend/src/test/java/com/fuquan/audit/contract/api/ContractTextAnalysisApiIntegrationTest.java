package com.fuquan.audit.contract.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult.Hint;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisUseCase;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractTextAnalysisApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ContractTextAnalysisUseCase useCase;

    @Test
    void authenticatedProjectAnalysisReturnsExplicitAiProvenance() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(useCase.analyze(projectId, List.of(fileId))).thenReturn(new ContractTextAnalysisResult(
                true,
                List.of("合同范围已识别"),
                List.of(new Hint(ContractTextAnalysisResult.SemanticCategory.DELIVERY_TERMS,
                        ContractTextAnalysisResult.Attention.REVIEW,
                        "工期表述", "建议人工核对", List.of(fileId))),
                "qwen-test",
                7L));

        mockMvc.perform(post("/api/v1/projects/{projectId}/ai/contract-text-analysis", projectId)
                        .header("X-Request-Id", "contract-ai-api-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("fileIds", List.of(fileId))))
                        .with(authentication(auth("AUDITOR")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "contract-ai-api-request"))
                .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.data.operation").value("CONTRACT_TEXT_ANALYSIS"))
                .andExpect(jsonPath("$.data.requestId").value("contract-ai-api-request"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.summaries[0]").value("合同范围已识别"))
                .andExpect(jsonPath("$.data.hints[0].category").value("DELIVERY_TERMS"))
                .andExpect(jsonPath("$.data.hints[0].attention").value("REVIEW"))
                .andExpect(jsonPath("$.data.hints[0].evidenceRefs[0]").value(fileId.toString()))
                .andExpect(jsonPath("$.data.model").value("qwen-test"))
                .andExpect(jsonPath("$.data.durationMs").value(7));
        verify(useCase).analyze(projectId, List.of(fileId));
    }

    @Test
    void projectAnalysisRejectsEmptyDuplicateAndOversizedFileSelections() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID duplicate = UUID.randomUUID();
        List<UUID> oversized = java.util.stream.IntStream.range(0, 51)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        List<List<UUID>> invalidSelections = List.of(List.of(), List.of(duplicate, duplicate), oversized);
        for (List<UUID> fileIds : invalidSelections) {
            mockMvc.perform(post("/api/v1/projects/{projectId}/ai/contract-text-analysis", projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("fileIds", fileIds)))
                            .with(authentication(auth("AUDITOR")))
                            .with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(useCase);
    }

    @Test
    void projectAnalysisRequiresAuthenticationAndCsrf() throws Exception {
        UUID projectId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("fileIds", List.of(UUID.randomUUID())));
        mockMvc.perform(post("/api/v1/projects/{projectId}/ai/contract-text-analysis", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/projects/{projectId}/ai/contract-text-analysis", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void oversizedModelInputAsksForSmallerSelectionInsteadOfGatewayRetries() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(useCase.analyze(projectId, List.of(fileId))).thenThrow(
                new com.fuquan.audit.common.exception.ExternalServiceException(
                        com.fuquan.audit.common.exception.ExternalServiceType.AI,
                        new com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure(
                                com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure.Reason.MODEL_INPUT_TOO_LARGE)));
        mockMvc.perform(post("/api/v1/projects/{projectId}/ai/contract-text-analysis", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("fileIds", List.of(fileId))))
                        .with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已上传文件保留")));
    }

    private static Authentication auth(String role) {
        CurrentUser user = new CurrentUser("contract-ai-user", "contract.ai", "Contract AI", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
