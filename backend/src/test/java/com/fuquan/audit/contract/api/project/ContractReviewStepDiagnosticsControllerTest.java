package com.fuquan.audit.contract.api.project;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService.Diagnostics;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService.DiagnosticCallOutcome;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService.FailureStage;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewStepDiagnosticsService.ModelCallDiagnostic;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractReviewStepDiagnosticsControllerTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID STEP = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String PATH = "/api/v1/projects/" + PROJECT + "/contracts/" + CONTRACT
            + "/review-runs/" + RUN + "/steps/" + STEP + "/diagnostics";
    @Autowired MockMvc mvc;
    @MockBean ContractReviewStepDiagnosticsService diagnostics;
    @MockBean AuthContext auth;

    @BeforeEach
    void setUp() {
        when(auth.currentUser()).thenReturn(Optional.of(new CurrentUser(
                "owner", "owner", "Owner", Set.of("AUDITOR"))));
        when(diagnostics.get(PROJECT, CONTRACT, RUN, STEP, "owner")).thenReturn(result());
    }

    @Test
    void auditorReadsOnlySafeDurableFields() throws Exception {
        mvc.perform(get(PATH).with(user("owner").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.data.failureStage").value("TYPED_SCHEMA"))
                .andExpect(jsonPath("$.data.failureReasonCode").value("INVALID_TYPED_SCHEMA"))
                .andExpect(jsonPath("$.data.modelCalls[0].callOrdinal").value(1))
                .andExpect(jsonPath("$.data.modelCalls[0].safeReason").value("INVALID_TYPED_SCHEMA"))
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.rawResponse").doesNotExist())
                .andExpect(jsonPath("$.data.modelCalls[0].requestBody").doesNotExist());
    }

    @Test
    void systemAdminCanReadButOtherExistingBusinessRolesCannot() throws Exception {
        mvc.perform(get(PATH).with(user("owner").roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).with(user("owner").roles("PROCUREMENT_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(user("owner").roles("LEGAL")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void provenUnknownCallIsSerializedAsUnknownNotFailed() throws Exception {
        when(diagnostics.get(PROJECT, CONTRACT, RUN, STEP, "owner")).thenReturn(new Diagnostics(
                RUN, STEP, ContractReviewStepType.SEMANTIC_AI, ContractReviewStepStatus.UNKNOWN,
                "SEMANTIC_OUTCOME_UNKNOWN", ATTEMPT, 1, ContractReviewStepStatus.UNKNOWN,
                "SEMANTIC_OUTCOME_UNKNOWN", ReviewStepExternalCallState.OUTCOME_UNKNOWN,
                "a".repeat(64), "OLLAMA_PRIVATE", "approved-model", List.of(new ModelCallDiagnostic(
                        1, CallPhase.TRANSPORT, DiagnosticCallOutcome.UNKNOWN, "TRANSPORT_OUTCOME_UNKNOWN",
                        120001, null, 4006, 6144, 10150)), FailureStage.UNKNOWN, "TRANSPORT_OUTCOME_UNKNOWN"));

        mvc.perform(get(PATH).with(user("owner").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalCallState").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.data.modelCalls[0].outcome").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.modelCalls[0].safeReason").value("TRANSPORT_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.data.failureStage").value("UNKNOWN"));
    }

    @Test
    void outputLimitHasAnExplicitSafeGenerationClassification() throws Exception {
        when(diagnostics.get(PROJECT, CONTRACT, RUN, STEP, "owner")).thenReturn(new Diagnostics(
                RUN, STEP, ContractReviewStepType.SEMANTIC_AI, ContractReviewStepStatus.FAILED,
                "SEMANTIC_MODEL_INVALID_RESULT", ATTEMPT, 1, ContractReviewStepStatus.FAILED,
                "SEMANTIC_MODEL_INVALID_RESULT", ReviewStepExternalCallState.COMPLETED,
                "a".repeat(64), "OLLAMA_PRIVATE", "approved-model", List.of(new ModelCallDiagnostic(
                        1, CallPhase.TRANSPORT, CallOutcome.FAILED, "OUTPUT_TOKEN_LIMIT",
                        269055, "LENGTH", 4006, 6144, 10150)), FailureStage.PROVIDER_GENERATION, "OUTPUT_TOKEN_LIMIT"));

        mvc.perform(get(PATH).with(user("owner").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelCalls[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.modelCalls[0].finishReason").value("LENGTH"))
                .andExpect(jsonPath("$.data.failureStage").value("PROVIDER_GENERATION"))
                .andExpect(jsonPath("$.data.failureReasonCode").value("OUTPUT_TOKEN_LIMIT"));
    }

    private static Diagnostics result() {
        return new Diagnostics(RUN, STEP, ContractReviewStepType.SEMANTIC_AI,
                ContractReviewStepStatus.FAILED, "SEMANTIC_MODEL_INVALID_RESULT", ATTEMPT, 1,
                ContractReviewStepStatus.FAILED, "SEMANTIC_MODEL_INVALID_RESULT",
                ReviewStepExternalCallState.COMPLETED, "a".repeat(64), "OLLAMA_PRIVATE",
                "approved-model", List.of(new ModelCallDiagnostic(1, CallPhase.TRANSPORT,
                        CallOutcome.FAILED, "INVALID_TYPED_SCHEMA", 12, "STOP", 5, 10, 15)),
                FailureStage.TYPED_SCHEMA, "INVALID_TYPED_SCHEMA");
    }
}
