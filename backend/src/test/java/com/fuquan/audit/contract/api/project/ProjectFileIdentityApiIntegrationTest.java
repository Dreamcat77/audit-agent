package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationTransactionCoordinator;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProjectFileIdentityApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ExtractionResultRepository extractions;
    @Autowired ContractCandidateRepository candidates;
    @Autowired ContractCandidateMaterializationTransactionCoordinator materializationTransactions;
    @Autowired InMemoryIdempotencyAdapter idempotency;
    @Autowired InMemoryOperationAuditAdapter audits;

    @Test
    void assessmentAndHumanDecisionHaveOwnedReadOnlyHttpReopenAndCurrentProjection() throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String otherProjectId = createConfirmedProject(actor);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", "001.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx("项目编号：GZU-2026-001\n项目名称：贵州大学实验室设备采购项目")))
                        .header("Idempotency-Key", "identity-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String fileId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        String parseId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.parseResultId");
        extractions.insertIfAbsent(new ExtractionResult(UUID.randomUUID(), UUID.fromString(projectId),
                UUID.fromString(fileId), UUID.fromString(parseId), null, sha("synthetic-project-no-" + fileId),
                "facts-v2", "PROJECT_NO", null, "GZU-2026-001", null, null,
                "项目编号 GZU-2026-001", 1, 1, "paragraph:1", Instant.now()));

        MvcResult assessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "identity-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LIKELY_SAME_PROJECT"))
                .andExpect(jsonPath("$.data.assessmentId").isNotEmpty()).andReturn();
        String assessmentId = JsonPath.read(assessed.getResponse().getContentAsString(), "$.data.assessmentId");
        int claimCount = idempotency.size(), auditCount = audits.snapshot().size();
        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments/{assessmentId}",
                        projectId, fileId, assessmentId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId))
                .andExpect(jsonPath("$.data.parseResultId").value(parseId))
                .andExpect(jsonPath("$.data.evidence").isNotEmpty())
                .andExpect(jsonPath("$.data.replayed").value(false));
        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments/{assessmentId}",
                        otherProjectId, fileId, assessmentId).with(authentication(actor)))
                .andExpect(status().isNotFound());
        assertThat(idempotency.size()).isEqualTo(claimCount);
        assertThat(audits.snapshot()).hasSize(auditCount);

        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "identity-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\"," +
                                "\"expectedVersion\":0,\"reason\":\"synthetic check\"}").formatted(assessmentId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("CONFIRM_SAME_PROJECT"))
                .andExpect(jsonPath("$.data.decidedBy").isNotEmpty()).andReturn();
        String decisionId = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decisionId");
        int afterClaim = idempotency.size(), afterAudit = audits.snapshot().size();
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-decisions/{decisionId}",
                        projectId, fileId, decisionId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(decisionId))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.replayed").value(false));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-decisions/{decisionId}",
                        projectId, UUID.randomUUID(), decisionId).with(authentication(actor)))
                .andExpect(status().isNotFound());
        assertThat(idempotency.size()).isEqualTo(afterClaim);
        assertThat(audits.snapshot()).hasSize(afterAudit);
        mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effectiveIdentityStatus").value("SAME_PROJECT"))
                .andExpect(jsonPath("$.data[0].identityDecisionId").value(decisionId))
                .andExpect(jsonPath("$.data[0].identityCurrent").value(true))
                .andExpect(jsonPath("$.data[0].excludedFromProjectUse").value(false));
    }

    @Test
    void genericContractRoleFirstWaitsForHumanProjectIdentityThenMaterializesExactPairWithoutReparse()
            throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", "001.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx("""
                                        采购合同
                                        项目编号：GZU-2026-001
                                        合同编号：SYN-001
                                        甲方：合成采购单位
                                        乙方：合成供应商
                                        第一条 合同价款
                                        第二条 履行期限
                                        第三条 违约责任
                                        双方签字盖章后生效。
                                        """)))
                        .header("Idempotency-Key", "identity-contract-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String fileId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        String parseId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.parseResultId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();
        extractions.insertIfAbsent(new ExtractionResult(UUID.randomUUID(), UUID.fromString(projectId),
                UUID.fromString(fileId), UUID.fromString(parseId), null, sha("identity-no-" + fileId),
                "facts-v2", "PROJECT_NO", null, "GZU-2026-001", null, null,
                "项目编号 GZU-2026-001", 1, 1, "paragraph:1", Instant.now()));

        MvcResult roleAssessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "identity-role-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedRole").value("CONTRACT_BODY")).andReturn();
        String roleAssessmentId = JsonPath.read(roleAssessed.getResponse().getContentAsString(),
                "$.data.assessmentId");
        MvcResult roleConfirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "identity-role-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\"," +
                                "\"expectedVersion\":0}").formatted(roleAssessmentId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus")
                        .value("WAITING_FOR_PROJECT_IDENTITY"))
                .andExpect(jsonPath("$.data.candidateId").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        String roleDecisionId = JsonPath.read(roleConfirmed.getResponse().getContentAsString(), "$.data.decisionId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();

        MvcResult identityAssessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "identity-pair-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LIKELY_SAME_PROJECT")).andReturn();
        String identityAssessmentId = JsonPath.read(identityAssessed.getResponse().getContentAsString(),
                "$.data.assessmentId");
        MvcResult identityConfirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "identity-pair-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\"," +
                                "\"expectedVersion\":0}").formatted(identityAssessmentId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").isNotEmpty()).andReturn();
        String identityDecisionId = JsonPath.read(identityConfirmed.getResponse().getContentAsString(),
                "$.data.decisionId");
        String candidateId = JsonPath.read(identityConfirmed.getResponse().getContentAsString(),
                "$.data.candidateId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(1);
        assertThat(idempotency.findByScopeAndKey("CONTRACT_CANDIDATE_MATERIALIZE",
                "role-decision:" + roleDecisionId + ":identity-decision:" + identityDecisionId))
                .isPresent();
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceParseResultId").value(parseId))
                .andExpect(jsonPath("$.data.sourceRoleDecisionId").value(roleDecisionId))
                .andExpect(jsonPath("$.data.sourceIdentityDecisionId").value(identityDecisionId))
                .andExpect(jsonPath("$.data.evidence").isNotEmpty());
    }

    @Test
    void identityFirstDoesNotCreateCandidateUntilSameFileHasHumanContractRole() throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String parseId = parseId(projectId, fileId, actor);
        String identityAssessmentId = identityAssessment(projectId, fileId, actor);
        MvcResult identity = identityConfirmation(projectId, fileId, identityAssessmentId,
                "CONFIRM_SAME_PROJECT", 0, actor);
        String identityDecisionId = JsonPath.read(identity.getResponse().getContentAsString(), "$.data.decisionId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();

        String roleAssessmentId = roleAssessment(projectId, fileId, actor);
        MvcResult role = roleConfirmation(projectId, fileId, roleAssessmentId,
                "CONTRACT_BODY", 0, actor);
        String candidateId = JsonPath.read(role.getResponse().getContentAsString(), "$.data.candidateId");
        String roleDecisionId = JsonPath.read(role.getResponse().getContentAsString(), "$.data.decisionId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                .satisfies(value -> {
                    assertThat(value.id().toString()).isEqualTo(candidateId);
                    assertThat(value.sourceParseResultId().toString()).isEqualTo(parseId);
                    assertThat(value.sourceRoleDecisionId().toString()).isEqualTo(roleDecisionId);
                    assertThat(value.sourceIdentityDecisionId().toString()).isEqualTo(identityDecisionId);
                });
        assertThat(parseId(projectId, fileId, actor)).isEqualTo(parseId);
    }

    @Test
    void draftNotSameIsRetainedAndUnpromotableThenSameReauthorizesWithoutDuplicate() throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String identityAssessmentId = identityAssessment(projectId, fileId, actor);
        MvcResult firstIdentity = identityConfirmation(projectId, fileId, identityAssessmentId,
                "CONFIRM_SAME_PROJECT", 0, actor);
        String firstIdentityDecisionId = JsonPath.read(firstIdentity.getResponse().getContentAsString(),
                "$.data.decisionId");
        String roleAssessmentId = roleAssessment(projectId, fileId, actor);
        MvcResult role = roleConfirmation(projectId, fileId, roleAssessmentId,
                "CONTRACT_BODY", 0, actor);
        String candidateId = JsonPath.read(role.getResponse().getContentAsString(), "$.data.candidateId");
        String roleDecisionId = JsonPath.read(role.getResponse().getContentAsString(), "$.data.decisionId");

        MvcResult negative = identityConfirmation(projectId, fileId, identityAssessmentId,
                "CONFIRM_NOT_SAME_PROJECT", 1, actor);
        assertThat(JsonPath.<String>read(negative.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isEqualTo("WAITING_FOR_PROJECT_IDENTITY");
        assertThat(JsonPath.<String>read(negative.getResponse().getContentAsString(),
                "$.data.candidateId")).isEqualTo(candidateId);
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, fileId, roleDecisionId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus")
                        .value("WAITING_FOR_PROJECT_IDENTITY"));
        assertThatThrownBy(() -> materializationTransactions.materialize(UUID.fromString(projectId),
                UUID.fromString(fileId), UUID.fromString(roleDecisionId),
                UUID.fromString(firstIdentityDecisionId), (CurrentUser) actor.getPrincipal(),
                new ProjectRequestMetadata("test", "test", null), UUID.randomUUID(), Instant.now()))
                .hasMessageContaining("candidate");
        mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effectiveIdentityStatus").value("NOT_SAME_PROJECT"))
                .andExpect(jsonPath("$.data[0].excludedFromProjectUse").value(true));
        assertThat(candidates.findByProjectIdAndId(UUID.fromString(projectId), UUID.fromString(candidateId)))
                .isPresent().get().extracting(value -> value.promotionState().name()).isEqualTo("DRAFT");
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", "identity-negative-promote-" + UUID.randomUUID())
                        .contentType("application/json").content(promotionBody(0))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());

        MvcResult restored = identityConfirmation(projectId, fileId, identityAssessmentId,
                "CONFIRM_SAME_PROJECT", 2, actor);
        String restoredIdentityId = JsonPath.read(restored.getResponse().getContentAsString(), "$.data.decisionId");
        assertThat(JsonPath.<String>read(restored.getResponse().getContentAsString(), "$.data.candidateId"))
                .isEqualTo(candidateId);
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                .satisfies(value -> {
                    assertThat(value.id().toString()).isEqualTo(candidateId);
                    assertThat(value.versionNo()).isEqualTo(1);
                    assertThat(value.sourceIdentityDecisionId().toString()).isEqualTo(restoredIdentityId);
                });
        assertThatThrownBy(() -> materializationTransactions.materialize(UUID.fromString(projectId),
                UUID.fromString(fileId), UUID.fromString(roleDecisionId),
                UUID.fromString(firstIdentityDecisionId), (CurrentUser) actor.getPrincipal(),
                new ProjectRequestMetadata("test", "test", null), UUID.randomUUID(), Instant.now()))
                .hasMessageContaining("candidate");
        confirmAndPromote(projectId, candidateId, 1, actor);
        var promoted = candidates.findByProjectIdAndId(UUID.fromString(projectId),
                UUID.fromString(candidateId)).orElseThrow();
        MvcResult laterSame = identityConfirmation(projectId, fileId, identityAssessmentId,
                "CONFIRM_SAME_PROJECT", 3, actor);
        assertThat(JsonPath.<String>read(laterSame.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isEqualTo("ALREADY_PROMOTED");
        assertThat(candidates.findByProjectIdAndId(UUID.fromString(projectId), UUID.fromString(candidateId)))
                .contains(promoted);
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", "identity-promoted-not-same-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\","
                                + "\"resolution\":\"CONFIRM_NOT_SAME_PROJECT\","
                                + "\"expectedVersion\":4}").formatted(identityAssessmentId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());
        assertThat(candidates.findByProjectIdAndId(UUID.fromString(projectId), UUID.fromString(candidateId)))
                .contains(promoted);
    }

    @Test
    void projectVersionChangeMakesOldSameHistoricalUntilNewAssessmentAndDecisionReauthorizeDraft()
            throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String a1 = identityAssessment(projectId, fileId, actor);
        MvcResult d1 = identityConfirmation(projectId, fileId, a1, "CONFIRM_SAME_PROJECT", 0, actor);
        String oldDecisionId = JsonPath.read(d1.getResponse().getContentAsString(), "$.data.decisionId");
        String roleAssessmentId = roleAssessment(projectId, fileId, actor);
        String candidateId = JsonPath.read(roleConfirmation(projectId, fileId, roleAssessmentId,
                "CONTRACT_BODY", 0, actor).getResponse().getContentAsString(), "$.data.candidateId");

        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "identity-metadata-change-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"name\":\"Changed synthetic project\","
                                + "\"projectNo\":\"OTHER-2026-999\"}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionNo").value(2));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-decisions/{decisionId}",
                        projectId, fileId, oldDecisionId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectVersionNo").value(1));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].identityCurrent").value(false))
                .andExpect(jsonPath("$.data[0].effectiveIdentityStatus").value("INSUFFICIENT"));
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", "identity-stale-promote-" + UUID.randomUUID())
                        .contentType("application/json").content(promotionBody(0))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());

        String a2 = identityAssessment(projectId, fileId, actor);
        MvcResult d2 = identityConfirmation(projectId, fileId, a2, "CONFIRM_SAME_PROJECT", 1, actor);
        String newDecisionId = JsonPath.read(d2.getResponse().getContentAsString(), "$.data.decisionId");
        assertThat(JsonPath.<String>read(d2.getResponse().getContentAsString(), "$.data.candidateId"))
                .isEqualTo(candidateId);
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                .satisfies(value -> {
                    assertThat(value.sourceIdentityDecisionId().toString()).isEqualTo(newDecisionId);
                    assertThat(value.versionNo()).isEqualTo(1);
                });
    }

    @Test
    void assessmentOnlyCanBeRediscoveredAfterProjectMetadataChange() throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String assessmentId = identityAssessment(projectId, fileId, actor);
        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "identity-assessment-drift-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"name\":\"Changed synthetic project\","
                                + "\"projectNo\":\"OTHER-2026-999\"}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].identityAssessmentId").value(assessmentId))
                .andExpect(jsonPath("$.data[0].identityCurrent").value(false))
                .andExpect(jsonPath("$.data[0].effectiveIdentityStatus").value("INSUFFICIENT"));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-assessments/{assessmentId}",
                        projectId, fileId, assessmentId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId));
    }

    @Test
    void threeIndependentUploadsDoNotShareProjectIdentityAuthorityOrRequireBatchCompletion()
            throws Exception {
        Authentication actor = auth("AUDITOR");
        String projectId = createConfirmedProject(actor);
        String a = uploadSyntheticContract(projectId, actor, "001.docx", "GZU-2026-001");
        String b = uploadSyntheticContract(projectId, actor, "材料2.docx", "OTHER-2026-999");
        insertProjectNoFact(projectId, a, "GZU-2026-001");
        insertProjectNoFact(projectId, b, "OTHER-2026-999");
        MvcResult aAssessment = identityAssessmentResponse(projectId, a, actor);
        MvcResult bAssessment = identityAssessmentResponse(projectId, b, actor);
        assertThat(JsonPath.<String>read(aAssessment.getResponse().getContentAsString(), "$.data.status"))
                .isEqualTo("LIKELY_SAME_PROJECT");
        assertThat(JsonPath.<String>read(bAssessment.getResponse().getContentAsString(), "$.data.status"))
                .isEqualTo("CONFLICT");
        String aAssessmentId = JsonPath.read(aAssessment.getResponse().getContentAsString(), "$.data.assessmentId");
        String bAssessmentId = JsonPath.read(bAssessment.getResponse().getContentAsString(), "$.data.assessmentId");
        String aDecisionId = JsonPath.read(identityConfirmation(projectId, a, aAssessmentId,
                "CONFIRM_SAME_PROJECT", 0, actor).getResponse().getContentAsString(), "$.data.decisionId");
        String aCandidate = JsonPath.read(roleConfirmation(projectId, a, roleAssessment(projectId, a, actor),
                "CONTRACT_BODY", 0, actor).getResponse().getContentAsString(), "$.data.candidateId");
        MvcResult bRole = roleConfirmation(projectId, b, roleAssessment(projectId, b, actor),
                "CONTRACT_BODY", 0, actor);
        assertThat(JsonPath.<String>read(bRole.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isEqualTo("WAITING_FOR_PROJECT_IDENTITY");
        identityConfirmation(projectId, b, bAssessmentId, "CONFIRM_NOT_SAME_PROJECT", 0, actor);
        assertThat(candidates.findByProjectId(UUID.fromString(projectId)))
                .singleElement().satisfies(value -> assertThat(value.id().toString()).isEqualTo(aCandidate));

        MvcResult cUploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", "附件.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx("附件\n仅包含合成项目的一般说明。")))
                        .header("Idempotency-Key", "identity-third-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String c = JsonPath.read(cUploaded.getResponse().getContentAsString(), "$.data.id");
        MvcResult cAssessment = identityAssessmentResponse(projectId, c, actor);
        assertThat(JsonPath.<String>read(cAssessment.getResponse().getContentAsString(), "$.data.status"))
                .isEqualTo("INSUFFICIENT");
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-decisions/{decisionId}",
                        projectId, a, aDecisionId).with(authentication(actor)))
                .andExpect(status().isOk());
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(1);
    }

    @Test
    void identityCommandsKeepAuthenticationCsrfRoleGuardAndIdempotencyKey() throws Exception {
        String route = "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments";
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "anonymous").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "no-csrf").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(route, UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "wrong-role")
                        .with(csrf()).with(authentication(auth("PROJECT_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void identityConfirmationRequiresExplicitExpectedVersionForItsCasFence() throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String assessmentId = identityAssessment(projectId, fileId, actor);
        String route = "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations";
        for (String body : List.of(
                ("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\"}")
                        .formatted(assessmentId),
                ("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                        + "\"expectedVersion\":null}").formatted(assessmentId))) {
            mockMvc.perform(post(route, projectId, fileId)
                            .header("Idempotency-Key", "identity-missing-version-" + UUID.randomUUID())
                            .contentType("application/json").content(body)
                            .with(csrf()).with(authentication(actor)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void pairMaterializationReplaysAcrossDifferentHumanActorsInBothConfirmationOrders() throws Exception {
        Authentication roleActor = auth("LEGAL"), identityActor = auth("AUDITOR");
        String projectId = createConfirmedProject(roleActor);

        String roleFirstFile = uploadSyntheticContract(projectId, roleActor);
        String firstRoleAssessment = roleAssessment(projectId, roleFirstFile, roleActor);
        String firstRoleKey = "cross-actor-role-first-" + UUID.randomUUID();
        MvcResult waiting = roleConfirmation(projectId, roleFirstFile, firstRoleAssessment,
                "CONTRACT_BODY", 0, roleActor, firstRoleKey);
        assertThat(JsonPath.<String>read(waiting.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isEqualTo("WAITING_FOR_PROJECT_IDENTITY");
        String firstIdentityAssessment = identityAssessment(projectId, roleFirstFile, identityActor);
        String firstCandidate = JsonPath.read(identityConfirmation(projectId, roleFirstFile,
                firstIdentityAssessment, "CONFIRM_SAME_PROJECT", 0, identityActor)
                .getResponse().getContentAsString(), "$.data.candidateId");
        MvcResult roleReplay = roleConfirmation(projectId, roleFirstFile, firstRoleAssessment,
                "CONTRACT_BODY", 0, roleActor, firstRoleKey);
        assertThat(JsonPath.<String>read(roleReplay.getResponse().getContentAsString(), "$.data.candidateId"))
                .isEqualTo(firstCandidate);
        assertThat(JsonPath.<Boolean>read(roleReplay.getResponse().getContentAsString(), "$.data.replayed"))
                .isTrue();

        String identityFirstFile = uploadSyntheticContract(projectId, identityActor);
        String secondIdentityAssessment = identityAssessment(projectId, identityFirstFile, identityActor);
        String secondIdentityKey = "cross-actor-identity-first-" + UUID.randomUUID();
        MvcResult identityBeforeRole = identityConfirmation(projectId, identityFirstFile,
                secondIdentityAssessment, "CONFIRM_SAME_PROJECT", 0, identityActor, secondIdentityKey);
        assertThat(JsonPath.<String>read(identityBeforeRole.getResponse().getContentAsString(),
                "$.data.candidateId")).isNull();
        String secondRoleAssessment = roleAssessment(projectId, identityFirstFile, roleActor);
        String secondCandidate = JsonPath.read(roleConfirmation(projectId, identityFirstFile,
                secondRoleAssessment, "CONTRACT_BODY", 0, roleActor)
                .getResponse().getContentAsString(), "$.data.candidateId");
        MvcResult identityReplay = identityConfirmation(projectId, identityFirstFile,
                secondIdentityAssessment, "CONFIRM_SAME_PROJECT", 0, identityActor, secondIdentityKey);
        assertThat(JsonPath.<String>read(identityReplay.getResponse().getContentAsString(), "$.data.candidateId"))
                .isEqualTo(secondCandidate);
        assertThat(JsonPath.<Boolean>read(identityReplay.getResponse().getContentAsString(), "$.data.replayed"))
                .isTrue();
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(2);
    }

    @Test
    void historicalContractRoleConfirmationReplaysDecisionWithoutReauthorizingAfterRoleOverride()
            throws Exception {
        Authentication actor = auth("LEGAL");
        String projectId = createConfirmedProject(actor);
        String fileId = uploadSyntheticContract(projectId, actor);
        String identityAssessmentId = identityAssessment(projectId, fileId, actor);
        String identityDecisionId = JsonPath.read(identityConfirmation(projectId, fileId,
                identityAssessmentId, "CONFIRM_SAME_PROJECT", 0, actor)
                .getResponse().getContentAsString(), "$.data.decisionId");
        String roleAssessmentId = roleAssessment(projectId, fileId, actor);
        String originalKey = "historical-body-role-" + UUID.randomUUID();
        MvcResult body = roleConfirmation(projectId, fileId, roleAssessmentId,
                "CONTRACT_BODY", 0, actor, originalKey);
        String oldDecisionId = JsonPath.read(body.getResponse().getContentAsString(), "$.data.decisionId");
        String candidateId = JsonPath.read(body.getResponse().getContentAsString(), "$.data.candidateId");
        roleConfirmation(projectId, fileId, roleAssessmentId, "OTHER", 1, actor);
        MvcResult replay = roleConfirmation(projectId, fileId, roleAssessmentId,
                "CONTRACT_BODY", 0, actor, originalKey);
        assertThat(JsonPath.<String>read(replay.getResponse().getContentAsString(), "$.data.decisionId"))
                .isEqualTo(oldDecisionId);
        assertThat(JsonPath.<Boolean>read(replay.getResponse().getContentAsString(), "$.data.replayed"))
                .isTrue();
        assertThat(JsonPath.<String>read(replay.getResponse().getContentAsString(),
                "$.data.candidateMaterializationStatus")).isNull();
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, fileId, oldDecisionId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus")
                        .value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/identity-decisions/{decisionId}",
                        projectId, fileId, identityDecisionId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("NOT_APPLICABLE"));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                .satisfies(value -> assertThat(value.id().toString()).isEqualTo(candidateId));
    }

    private String uploadSyntheticContract(String projectId, Authentication actor) throws Exception {
        return uploadSyntheticContract(projectId, actor, "001.docx", "GZU-2026-001");
    }
    private String uploadSyntheticContract(String projectId, Authentication actor,
            String filename, String projectNo) throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", filename,
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx("采购合同\n项目编号：" + projectNo + "\n合同编号：SYN-" + UUID.randomUUID()
                                        + "\n甲方：合成采购单位\n乙方：合成供应商\n第一条 合同价款"
                                        + "\n第二条 履行期限\n第三条 违约责任\n双方签字盖章后生效。")))
                        .header("Idempotency-Key", "identity-fixture-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
    }
    private String parseId(String projectId, String fileId, Authentication actor) throws Exception {
        MvcResult files = mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        List<java.util.Map<String, Object>> items = JsonPath.read(files.getResponse().getContentAsString(), "$.data");
        return String.valueOf(items.stream().filter(value -> fileId.equals(value.get("id")))
                .findFirst().orElseThrow().get("parseResultId"));
    }
    private String identityAssessment(String projectId, String fileId, Authentication actor) throws Exception {
        return JsonPath.read(identityAssessmentResponse(projectId, fileId, actor)
                .getResponse().getContentAsString(), "$.data.assessmentId");
    }
    private MvcResult identityAssessmentResponse(String projectId, String fileId,
            Authentication actor) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "identity-fixture-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
    }
    private void insertProjectNoFact(String projectId, String fileId, String projectNo) throws Exception {
        String parseId = parseId(projectId, fileId, auth("AUDITOR"));
        extractions.insertIfAbsent(new ExtractionResult(UUID.randomUUID(), UUID.fromString(projectId),
                UUID.fromString(fileId), UUID.fromString(parseId), null,
                sha("independent-project-no-" + fileId), "facts-v2", "PROJECT_NO", null,
                projectNo, null, null, "项目编号：" + projectNo, 1, 1, "paragraph:2", Instant.now()));
    }
    private MvcResult identityConfirmation(String projectId, String fileId, String assessmentId,
            String resolution, long version, Authentication actor) throws Exception {
        return identityConfirmation(projectId, fileId, assessmentId, resolution, version, actor,
                "identity-fixture-confirm-" + UUID.randomUUID());
    }
    private MvcResult identityConfirmation(String projectId, String fileId, String assessmentId,
            String resolution, long version, Authentication actor, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"%s\","
                                + "\"expectedVersion\":%d}").formatted(assessmentId, resolution, version))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
    }
    private String roleAssessment(String projectId, String fileId, Authentication actor) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "identity-fixture-role-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedRole").value("CONTRACT_BODY")).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.assessmentId");
    }
    private MvcResult roleConfirmation(String projectId, String fileId, String assessmentId,
            String role, long version, Authentication actor) throws Exception {
        return roleConfirmation(projectId, fileId, assessmentId, role, version, actor,
                "identity-fixture-role-confirm-" + UUID.randomUUID());
    }
    private MvcResult roleConfirmation(String projectId, String fileId, String assessmentId,
            String role, long version, Authentication actor, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"selectedRole\":\"%s\","
                                + "\"expectedVersion\":%d}").formatted(assessmentId, role, version))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
    }
    private static String promotionBody(long version) {
        return ("{\"expectedVersion\":%d,\"contractNo\":\"SYN-PROMOTE-%s\","
                + "\"contractName\":\"Synthetic Contract\",\"contractType\":\"GOODS\","
                + "\"partyA\":\"Buyer\",\"partyB\":\"Supplier\",\"contractAmount\":10.00,"
                + "\"lineItems\":[]}").formatted(version, UUID.randomUUID());
    }

    private void confirmAndPromote(String projectId, String candidateId, long baseVersion,
            Authentication actor) throws Exception {
        MvcResult saved = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", "identity-candidate-confirm-" + UUID.randomUUID())
                        .contentType("application/json").content(promotionBody(baseVersion))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String confirmationId = JsonPath.read(saved.getResponse().getContentAsString(), "$.data.confirmationId");
        long resultVersion = ((Number) JsonPath.read(saved.getResponse().getContentAsString(),
                "$.data.resultCandidateVersion")).longValue();
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .header("Idempotency-Key", "identity-candidate-promote-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":%d,\"confirmationId\":\"%s\"}"
                                .formatted(resultVersion, confirmationId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
    }

    private String createConfirmedProject(Authentication actor) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "identity-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(patch("/api/v1/projects/{projectId}", id)
                        .header("Idempotency-Key", "identity-metadata-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"贵州大学实验室设备采购项目\"," +
                                "\"projectNo\":\"GZU-2026-001\"}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        return id;
    }
    private static Authentication auth(String role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID().toString(), "identity.api", "Identity API",
                Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : text.lines().toList()) doc.createParagraph().createRun().setText(line);
            doc.write(output); return output.toByteArray();
        }
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
