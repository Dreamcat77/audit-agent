package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentException;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentPort;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProjectIntakeContinuityApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @MockBean DocumentRoleAssessmentPort documentRole;
    private static final ProjectFileRoleModelProvenance AI_PROVENANCE =
            new ProjectFileRoleModelProvenance("OPENAI_COMPATIBLE", "qwen-test",
                    "NOT_DECLARED_BY_PROVIDER", "document-role-prompt-v1", "document-role-v1");

    @BeforeEach
    void configureDocumentRole() {
        reset(documentRole);
        when(documentRole.configuredProvenance()).thenReturn(AI_PROVENANCE);
    }

    @Test
    void nativeDocxUploadPublishesParseAndDeterministicRoleAssessmentWithoutCreatingCandidate() throws Exception {
        Authentication actor = actor();
        String projectId = createConfirmedProject(actor);
        byte[] content = docx("""
                采购合同
                项目名称：智慧维修系统
                项目编号：WX-2026-001
                甲方：示例采购单位
                乙方：示例供应商
                合同编号：HT-2026-001
                合同金额：人民币 67000.00 元
                第一条 合同价款
                第二条 履行期限
                第三条 违约责任
                双方签字盖章后生效。
                """);
        MockMultipartFile file = new MockMultipartFile("file", "001.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        MvcResult upload = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file)
                        .header("Idempotency-Key", "continuity-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingestionStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.parseStatus").value("PARSED"))
                .andExpect(jsonPath("$.data.parseMethod").value("DOCX_TEXT"))
                .andExpect(jsonPath("$.data.parseResultId").isNotEmpty())
                .andExpect(jsonPath("$.data.roleAssessmentId").isNotEmpty())
                .andExpect(jsonPath("$.data.suggestedRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.roleDecisionStatus").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.data.progression.stage").value("NEEDS_ROLE_CONFIRMATION"))
                .andExpect(jsonPath("$.data.progression.blocker").value("NEEDS_ROLE_CONFIRMATION"))
                .andExpect(jsonPath("$.data.progression.availableActions[0]").value("CONFIRM_ROLE"))
                .andReturn();
        String fileId = com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(), "$.data.id");
        String roleAssessmentId = com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(), "$.data.roleAssessmentId");

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[?(@.id == '%s')].roleAssessmentId".formatted(fileId)).isNotEmpty())
                .andExpect(jsonPath("$.data.candidates.length()").value(0));

        String roleKey = "continuity-role-confirm-" + UUID.randomUUID();
        String roleBody = ("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\","
                + "\"expectedVersion\":0,\"reason\":\"continuity fixture\"}")
                .formatted(roleAssessmentId);
        MvcResult roleConfirmation = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", roleKey)
                        .contentType("application/json")
                        .content(roleBody)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus")
                        .value("WAITING_FOR_PROJECT_IDENTITY"))
                .andReturn();
        String roleDecisionId = com.jayway.jsonpath.JsonPath.read(
                roleConfirmation.getResponse().getContentAsString(), "$.data.decisionId");
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", roleKey).contentType("application/json")
                        .content(roleBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(roleDecisionId))
                .andExpect(jsonPath("$.data.replayed").value(true));

        MvcResult waitingForIdentity = mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].identityAssessmentId").isNotEmpty())
                .andExpect(jsonPath("$.data.files[0].identityDecisionStatus").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.data.files[0].progression.stage")
                        .value("NEEDS_IDENTITY_CONFIRMATION"))
                .andExpect(jsonPath("$.data.files[0].progression.availableActions[0]")
                        .value("CONFIRM_PROJECT_IDENTITY"))
                .andExpect(jsonPath("$.data.candidates.length()").value(0))
                .andReturn();
        String identityAssessmentId = com.jayway.jsonpath.JsonPath.read(
                waitingForIdentity.getResponse().getContentAsString(),
                "$.data.files[0].identityAssessmentId");

        String identityKey = "continuity-identity-confirm-" + UUID.randomUUID();
        String identityBody = ("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                + "\"expectedVersion\":0,\"reason\":\"continuity fixture\"}")
                .formatted(identityAssessmentId);
        MvcResult identityConfirmation = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", identityKey)
                        .contentType("application/json")
                        .content(identityBody)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").isNotEmpty())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("MATERIALIZED"))
                .andReturn();
        String identityDecisionId = com.jayway.jsonpath.JsonPath.read(
                identityConfirmation.getResponse().getContentAsString(), "$.data.decisionId");
        String candidateId = com.jayway.jsonpath.JsonPath.read(
                identityConfirmation.getResponse().getContentAsString(), "$.data.candidateId");
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", identityKey).contentType("application/json")
                        .content(identityBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(identityDecisionId))
                .andExpect(jsonPath("$.data.candidateId").value(candidateId))
                .andExpect(jsonPath("$.data.replayed").value(true));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].effectiveRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.files[0].effectiveRoleSource").value("HUMAN_CONFIRMATION"))
                .andExpect(jsonPath("$.data.files[0].effectiveIdentityStatus").value("SAME_PROJECT"))
                .andExpect(jsonPath("$.data.files[0].progression.stage")
                        .value("CONTRACT_INFORMATION_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.files[0].progression.blocker")
                        .value("CONTRACT_EVIDENCE_INCOMPLETE"))
                .andExpect(jsonPath("$.data.files[0].progression.availableActions.length()")
                        .value(0))
                .andExpect(jsonPath("$.data.candidates.length()").value(1));

        String confirmationKey = "continuity-candidate-confirm-" + UUID.randomUUID();
        String confirmationBody = """
                {"expectedVersion":0,"contractNo":"HT-2026-001","contractName":"智慧维修系统采购合同",
                 "contractType":"GOODS","partyA":"示例采购单位","partyB":"示例供应商",
                 "contractAmount":67000.00,"amountInWords":"人民币陆万柒仟元整","lineItems":[]}
                """;
        MvcResult candidateConfirmation = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", confirmationKey).contentType("application/json")
                        .content(confirmationBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCandidateVersion").value(1))
                .andReturn();
        String confirmationId = com.jayway.jsonpath.JsonPath.read(
                candidateConfirmation.getResponse().getContentAsString(), "$.data.confirmationId");
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", confirmationKey).contentType("application/json")
                        .content(confirmationBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationId").value(confirmationId))
                .andExpect(jsonPath("$.data.replayed").value(true));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].progression.stage").value("CANDIDATE_READY"))
                .andExpect(jsonPath("$.data.files[0].progression.availableActions[0]")
                        .value("PROMOTE_CONTRACT"));

        String promotionKey = "continuity-candidate-promote-" + UUID.randomUUID();
        String promotionBody = "{\"expectedVersion\":1,\"confirmationId\":\"%s\"}"
                .formatted(confirmationId);
        MvcResult promotion = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .header("Idempotency-Key", promotionKey).contentType("application/json")
                        .content(promotionBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String contractId = com.jayway.jsonpath.JsonPath.read(
                promotion.getResponse().getContentAsString(), "$.data.contractId");
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .header("Idempotency-Key", promotionKey).contentType("application/json")
                        .content(promotionBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(contractId));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].progression.stage").value("REVIEW_READY"))
                .andExpect(jsonPath("$.data.files[0].progression.availableActions[0]")
                        .value("START_REVIEW"))
                .andExpect(jsonPath("$.data.files[0].progression.contractId").value(contractId))
                .andExpect(jsonPath("$.data.associatedContracts.length()").value(1));
    }

    @Test
    void ambiguousDocumentUsesNonAuthoritativeAiProposalAndStillRequiresHumanRoleConfirmation() throws Exception {
        Authentication actor = actor();
        String projectId = createConfirmedProject(actor);
        when(documentRole.assess(any())).thenReturn(new DocumentRoleAssessmentPort.Result(
                DocumentRole.CONTRACT_BODY, new BigDecimal("0.91"),
                List.of(new DocumentRoleAssessmentPort.Candidate(
                        DocumentRole.CONTRACT_BODY, new BigDecimal("0.91"), "CONTRACT_TERMS")),
                List.of("CONTRACT_TERMS"), List.of("segment-1"), AI_PROVENANCE));

        MvcResult upload = uploadDocx(projectId, actor, "材料说明\n请人工查看本材料内容。", "ai-role-success");

        String body = upload.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.roleAssessmentSource"))
                .isEqualTo("MACHINE_AI");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.suggestedRole"))
                .isEqualTo("CONTRACT_BODY");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.roleDecisionStatus"))
                .isEqualTo("NEEDS_CONFIRMATION");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.aiRoleExecutionStatus"))
                .isEqualTo("COMPLETED");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.roleModelName"))
                .isEqualTo("qwen-test");
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(0));
        verify(documentRole).assess(any());
    }

    @Test
    void unavailableAiLeavesParseSuccessfulAndDeterministicUnknownOpenForHumanConfirmation() throws Exception {
        Authentication actor = actor();
        String projectId = createConfirmedProject(actor);
        when(documentRole.assess(any())).thenThrow(new DocumentRoleAssessmentException(
                DocumentRoleAssessmentException.Reason.UNAVAILABLE, null));

        MvcResult upload = uploadDocx(projectId, actor, "材料说明\n请人工查看本材料内容。", "ai-role-unavailable");

        String body = upload.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.parseStatus"))
                .isEqualTo("PARSED");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.roleAssessmentSource"))
                .isEqualTo("MACHINE_DETERMINISTIC");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.suggestedRole"))
                .isEqualTo("UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.aiRoleExecutionStatus"))
                .isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.aiRoleSafeReasonCode"))
                .isEqualTo("AI_ROLE_UNAVAILABLE");
        org.assertj.core.api.Assertions.assertThat(
                com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.progression.availableActions[0]"))
                .isEqualTo("CONFIRM_ROLE");
    }

    @Test
    void staleIdentityAssessmentResumesWithBoundInputAndSameKeyReplaysAfterStateAdvances()
            throws Exception {
        Authentication actor = actor();
        String projectId = createConfirmedProject(actor);
        MvcResult upload = uploadDocx(projectId, actor, """
                采购合同
                项目名称：智慧维修系统
                项目编号：WX-2026-001
                甲方：示例采购单位
                乙方：示例供应商
                合同金额：人民币 67000.00 元
                第一条 合同价款
                第二条 履行期限
                第三条 违约责任
                """, "progression-resume");
        String fileId = com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(), "$.data.id");
        String roleAssessmentId = com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(), "$.data.roleAssessmentId");
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", "resume-role-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\","
                                + "\"expectedVersion\":0,\"reason\":\"fixture\"}")
                                .formatted(roleAssessmentId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "resume-project-version-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"expectedVersion":1,"name":"智慧维修系统（复核）",
                                 "projectNo":"WX-2026-001","category":"维修服务"}
                                """)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2));

        MvcResult stale = mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].progression.blocker")
                        .value("IDENTITY_ASSESSMENT_STALE"))
                .andExpect(jsonPath("$.data.files[0].progression.availableActions[0]")
                        .value("RESUME_IDENTITY_ASSESSMENT"))
                .andExpect(jsonPath("$.data.files[0].progression.recoveryInputFingerprint").isNotEmpty())
                .andReturn();
        String fingerprint = com.jayway.jsonpath.JsonPath.read(
                stale.getResponse().getContentAsString(),
                "$.data.files[0].progression.recoveryInputFingerprint");
        String operationKey = "resume-stale-identity-" + UUID.randomUUID();
        String body = ("{\"expectedBlocker\":\"IDENTITY_ASSESSMENT_STALE\","
                + "\"expectedRecoveryInputFingerprint\":\"%s\"}").formatted(fingerprint);

        MvcResult resumed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/progression/resume",
                        projectId, fileId)
                        .header("Idempotency-Key", operationKey)
                        .contentType("application/json").content(body)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityAssessmentId").isNotEmpty())
                .andExpect(jsonPath("$.data.progression.blocker").value("NEEDS_IDENTITY_CONFIRMATION"))
                .andReturn();
        String recoveredAssessmentId = com.jayway.jsonpath.JsonPath.read(
                resumed.getResponse().getContentAsString(), "$.data.identityAssessmentId");

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/progression/resume",
                        projectId, fileId)
                        .header("Idempotency-Key", operationKey)
                        .contentType("application/json").content(body)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityAssessmentId").value(recoveredAssessmentId))
                .andExpect(jsonPath("$.data.progression.blocker").value("NEEDS_IDENTITY_CONFIRMATION"));

        String mismatched = ("{\"expectedBlocker\":\"IDENTITY_ASSESSMENT_STALE\","
                + "\"expectedRecoveryInputFingerprint\":\"%s\"}").formatted("f".repeat(64));
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/progression/resume",
                        projectId, fileId)
                        .header("Idempotency-Key", operationKey)
                        .contentType("application/json").content(mismatched)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.classification").value("IDEMPOTENCY_REQUEST_MISMATCH"));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].identityAssessmentId")
                        .value(recoveredAssessmentId));
    }

    private MvcResult uploadDocx(String projectId, Authentication actor, String text, String key)
            throws Exception {
        byte[] content = docx(text);
        MockMultipartFile file = new MockMultipartFile("file", "001.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);
        return mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", key + "-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
    }

    private String createConfirmedProject(Authentication actor) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "continuity-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "continuity-project-metadata-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"expectedVersion":0,"name":"智慧维修系统","projectNo":"WX-2026-001","category":"维修服务"}
                                """)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        return projectId;
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            text.lines().forEach(line -> document.createParagraph().createRun().setText(line));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static Authentication actor() {
        CurrentUser user = new CurrentUser("continuity-api-user", "continuity.api",
                "Continuity API", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }
}
