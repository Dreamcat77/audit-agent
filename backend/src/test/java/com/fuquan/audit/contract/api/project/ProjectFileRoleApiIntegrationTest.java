package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class ProjectFileRoleApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ProjectFileRepository files;
    @Autowired ProjectFileParseResultRepository parses;
    @Autowired ProjectFileRoleAssessmentRepository assessments;
    @Autowired ProjectFileRoleDecisionRepository decisions;
    @Autowired ContractCandidateRepository candidates;
    @Autowired FileStoragePort storage;
    @Autowired InMemoryIdempotencyAdapter idempotency;
    @Autowired InMemoryOperationAuditAdapter operationAudits;

    @ParameterizedTest(name = "{0} uses body signals to propose {2}")
    @MethodSource("roleFixtures")
    void genericFilenameMatrixIsClassifiedFromParsedBody(String fixture, String filename,
                                                          String expectedRole, String text) throws Exception {
        Authentication auth = auth("AUDITOR");
        String projectId = createProject(auth);
        String fileId = seedParsedFile(projectId, filename, text);

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "assessment-" + fixture + "-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.proposedRole").value(expectedRole))
                .andExpect(jsonPath("$.data.decisionStatus").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.data.parseResultId").isNotEmpty());

        Map<String, Object> projection = fileProjection(projectId, fileId, auth);
        assertThat(projection).containsEntry("originalFilename", filename)
                .containsEntry("suggestedRole", expectedRole)
                .containsEntry("roleExecutionStatus", "COMPLETED")
                .containsEntry("roleDecisionStatus", "NEEDS_CONFIRMATION")
                .containsEntry("effectiveRole", "UNKNOWN")
                .containsEntry("effectiveRoleSource", "NO_AUTHORITATIVE_ROLE");
        assertThat(projection.get("parseResultId")).isNotNull();
        assertThat(projection.get("parseVersion")).isEqualTo(1);
    }

    @Test
    void genericUploadedContractCreatesCandidateOnlyAfterHumanConfirmationAndReopensEvidence() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        byte[] bytes = docx(contractText());
        MockMultipartFile file = new MockMultipartFile("file", "001.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "generic-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.parseStatus").value("PARSED"))
                .andReturn();
        String fileId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        String parseResultId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.parseResultId");
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();
        confirmIdentity(projectId, fileId, auth);

        MvcResult assessment = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "generic-assessment-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposedRole").value("CONTRACT_BODY"))
                .andReturn();
        String assessmentId = JsonPath.read(assessment.getResponse().getContentAsString(), "$.data.assessmentId");
        String confirmationKey = "generic-confirm-" + UUID.randomUUID();
        String body = ("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\"," +
                "\"expectedVersion\":0,\"reason\":\"synthetic review\"}").formatted(assessmentId);

        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", confirmationKey).contentType("application/json")
                        .content(body).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").isNotEmpty())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("MATERIALIZED"))
                .andReturn();
        String candidateId = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.candidateId");
        String decisionId = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decisionId");

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", confirmationKey).contentType("application/json")
                        .content(body).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(decisionId))
                .andExpect(jsonPath("$.data.candidateId").value(candidateId))
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("REPLAYED"));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(1);

        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceParseResultId").value(parseResultId))
                .andExpect(jsonPath("$.data.sourceRoleDecisionId").value(decisionId))
                .andExpect(jsonPath("$.data.candidateAuthoritySource").value("HUMAN_CONFIRMATION"))
                .andExpect(jsonPath("$.data.sourceFactFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.data.candidate.sourceParseResultId").value(parseResultId))
                .andExpect(jsonPath("$.data.candidate.sourceRoleDecisionId").value(decisionId))
                .andExpect(jsonPath("$.data.candidate.sourceAuthoritySource").value("HUMAN_CONFIRMATION"))
                .andExpect(jsonPath("$.data.candidate.sourceFactFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.data.evidence").isNotEmpty());

        int candidateCount = candidates.findByProjectId(UUID.fromString(projectId)).size();
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, fileId, decisionId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(candidateId));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(candidateCount);
    }

    @Test
    void filenameContractCategoryNeverCreatesCurrentPipelineCandidateBeforeHumanConfirmation() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf", pdf(
                "Contract No: SYN-NAME-ONLY\nContract Amount: 10.00"));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "named-contract-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.effectiveRole").value("UNKNOWN"));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();
    }

    @Test
    void nonContractConfirmationIsNotApplicableAndDoesNotMaterializeCandidate() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = seedParsedFile(projectId, "扫描件.pdf", contractText());
        String assessmentId = assess(projectId, fileId, auth);

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", "award-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(assessmentId, "AWARD_NOTICE", 0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("NOT_APPLICABLE"));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();
    }

    @Test
    void draftCandidateRoleOverrideBlocksPromotionAndRestoringContractReusesCandidate() throws Exception {
        Authentication auth = auth("LEGAL");
        ConfirmedCandidate fixture = confirmedCandidate(auth);

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        fixture.projectId(), fixture.fileId())
                        .header("Idempotency-Key", "override-other-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(fixture.assessmentId(), "OTHER", 1))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("NOT_APPLICABLE"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        fixture.projectId(), fixture.fileId(), fixture.decisionId())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.candidateId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.candidateMaterializationStatus")
                        .value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        fixture.projectId(), fixture.candidateId())
                        .header("Idempotency-Key", "blocked-promotion-" + UUID.randomUUID())
                        .contentType("application/json").content(validPromotionBody(0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        assertThat(candidates.findByProjectId(UUID.fromString(fixture.projectId())))
                .singleElement().satisfies(candidate ->
                        assertThat(candidate.promotionState().name()).isEqualTo("DRAFT"));

        MvcResult restored = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        fixture.projectId(), fixture.fileId())
                        .header("Idempotency-Key", "restore-contract-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(fixture.assessmentId(), "CONTRACT_BODY", 2))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(fixture.candidateId()))
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("REPLAYED"))
                .andReturn();
        String restoredDecision = JsonPath.read(restored.getResponse().getContentAsString(), "$.data.decisionId");
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        fixture.projectId(), fixture.fileId(), fixture.decisionId())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.candidateId").value(org.hamcrest.Matchers.nullValue()));
        assertThat(candidates.findByProjectId(UUID.fromString(fixture.projectId())))
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.id().toString()).isEqualTo(fixture.candidateId());
                    assertThat(candidate.sourceRoleDecisionId().toString()).isEqualTo(restoredDecision);
                });
    }

    @Test
    void promotedCandidateRejectsNonContractRoleBeforeDecisionIsPersisted() throws Exception {
        Authentication auth = auth("LEGAL");
        ConfirmedCandidate fixture = confirmedCandidate(auth);
        confirmAndPromote(fixture.projectId(), fixture.candidateId(), 0, auth);

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        fixture.projectId(), fixture.fileId())
                        .header("Idempotency-Key", "invalid-divergence-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(fixture.assessmentId(), "OTHER", 1))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        assertThat(decisions.findLatestByFileId(UUID.fromString(fixture.fileId())).orElseThrow()
                .decisionId().toString()).isEqualTo(fixture.decisionId());
    }

    @Test
    void promotedCandidateCanReplayLaterContractConfirmationWithoutRewritingSource() throws Exception {
        Authentication auth = auth("LEGAL");
        ConfirmedCandidate fixture = confirmedCandidate(auth);
        confirmAndPromote(fixture.projectId(), fixture.candidateId(), 0, auth);
        String key = "promoted-body-reconfirm-" + UUID.randomUUID();
        String body = confirmationBody(fixture.assessmentId(), "CONTRACT_BODY", 1);
        String decisionId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult reopened = mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                            fixture.projectId(), fixture.fileId())
                            .header("Idempotency-Key", key).contentType("application/json")
                            .content(body).with(csrf()).with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidateId").value(fixture.candidateId()))
                    .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("ALREADY_PROMOTED"))
                    .andExpect(jsonPath("$.data.replayed").value(attempt == 1)).andReturn();
            decisionId = JsonPath.read(reopened.getResponse().getContentAsString(), "$.data.decisionId");
        }
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        fixture.projectId(), fixture.fileId(), decisionId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(fixture.candidateId()))
                .andExpect(jsonPath("$.data.candidateMaterializationStatus").value("ALREADY_PROMOTED"));
        assertThat(idempotency.findByScopeAndKey("CONTRACT_CANDIDATE_MATERIALIZE",
                "role-decision:" + decisionId)).isEmpty();
        assertThat(candidates.findByProjectId(UUID.fromString(fixture.projectId()))).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.promotionState().name()).isEqualTo("PROMOTED");
                    assertThat(candidate.sourceRoleDecisionId().toString()).isEqualTo(fixture.decisionId());
                });
    }

    @Test
    void historicalOtherConfirmationReplaysAfterLaterPromotionWithoutAuthorizingNewDivergence() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "001.docx", contractText());
        String assessmentId = assess(projectId, fileId, auth);
        confirmIdentity(projectId, fileId, auth);
        String firstKey = "historical-other-" + UUID.randomUUID();
        String firstBody = confirmationBody(assessmentId, "OTHER", 0);
        MvcResult first = mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", firstKey)
                        .contentType("application/json").content(firstBody)
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String firstDecisionId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.decisionId");

        MvcResult contract = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "later-contract-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(assessmentId, "CONTRACT_BODY", 1))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String candidateId = JsonPath.read(contract.getResponse().getContentAsString(), "$.data.candidateId");
        String latestDecisionId = JsonPath.read(contract.getResponse().getContentAsString(), "$.data.decisionId");
        confirmAndPromote(projectId, candidateId, 0, auth);

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", firstKey)
                        .contentType("application/json").content(firstBody)
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(firstDecisionId))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", "new-divergence-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(assessmentId, "OTHER", 2))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", firstKey)
                        .contentType("application/json")
                        .content(confirmationBody(assessmentId, "CONTRACT_BODY", 0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        assertThat(decisions.findLatestByFileId(UUID.fromString(fileId)).orElseThrow()
                .decisionId().toString()).isEqualTo(latestDecisionId);
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(1);
    }

    @Test
    void legacyCandidateHumanConfirmationReusesHistoricalCandidateWithoutReextracting() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        ContractCandidate legacy = seedLegacyCandidate(projectId, ProjectFileCategory.CONTRACT_BODY);
        String fileId = legacy.sourceContractFileId().toString();
        String assessmentId = assess(projectId, fileId, auth);
        String identityDecisionId = confirmIdentity(projectId, fileId, auth);
        String key = "legacy-body-reconfirm-" + UUID.randomUUID();
        String body = confirmationBody(assessmentId, "CONTRACT_BODY", 0);
        String decisionId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult reopened = mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                            projectId, fileId).header("Idempotency-Key", key)
                            .contentType("application/json").content(body)
                            .with(csrf()).with(authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidateId").value(legacy.id().toString())).andReturn();
            decisionId = JsonPath.read(reopened.getResponse().getContentAsString(), "$.data.decisionId");
        }
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, fileId, decisionId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(legacy.id().toString()));
        assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                .satisfies(reauthorized -> {
                    assertThat(reauthorized.id()).isEqualTo(legacy.id());
                    assertThat(reauthorized.contractNo()).isEqualTo(legacy.contractNo());
                    assertThat(reauthorized.versionNo()).isEqualTo(legacy.versionNo() + 1);
                    assertThat(reauthorized.sourceRoleDecisionId().toString()).isEqualTo(
                            decisions.findLatestByFileId(UUID.fromString(fileId)).orElseThrow()
                                    .decisionId().toString());
                    assertThat(reauthorized.sourceIdentityDecisionId().toString()).isEqualTo(identityDecisionId);
                });
        assertThat(parses.findLatestByFileId(legacy.sourceContractFileId()).orElseThrow().parseOrigin())
                .isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
    }

    @Test
    void concurrentSameConfirmationPublishesOneDecisionAndOneCandidate() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "材料2.docx", contractText());
        String assessmentId = assess(projectId, fileId, auth);
        confirmIdentity(projectId, fileId, auth);
        String key = "concurrent-confirm-" + UUID.randomUUID();
        String body = confirmationBody(assessmentId, "CONTRACT_BODY", 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> confirmation(projectId, fileId, key, body, auth, ready, go));
            var second = executor.submit(() -> confirmation(projectId, fileId, key, body, auth, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            MvcResult one = first.get(10, TimeUnit.SECONDS);
            MvcResult two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one, two)).extracting(result -> result.getResponse().getStatus())
                    .contains(200).allMatch(status -> status == 200 || status == 409);
            MvcResult winner = List.of(one, two).stream()
                    .filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
            MvcResult replay = mockMvc.perform(post(
                            "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                            .header("Idempotency-Key", key).contentType("application/json").content(body)
                            .with(csrf()).with(authentication(auth)))
                    .andExpect(status().isOk()).andReturn();
            assertThat(JsonPath.<String>read(winner.getResponse().getContentAsString(), "$.data.decisionId"))
                    .isEqualTo(JsonPath.read(replay.getResponse().getContentAsString(), "$.data.decisionId"));
            assertThat(JsonPath.<String>read(winner.getResponse().getContentAsString(), "$.data.candidateId"))
                    .isEqualTo(JsonPath.read(replay.getResponse().getContentAsString(), "$.data.candidateId"));
            assertThat(decisions.findLatestByFileId(UUID.fromString(fileId))).isPresent();
            assertThat(candidates.findByProjectId(UUID.fromString(projectId))).hasSize(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void distinctConfirmationRaceKeepsCandidateAuthorityAlignedWithWinningDecision() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "材料2.docx", contractText());
        String assessmentId = assess(projectId, fileId, auth);
        confirmIdentity(projectId, fileId, auth);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var contract = executor.submit(() -> confirmation(projectId, fileId,
                    "race-contract-" + UUID.randomUUID(),
                    confirmationBody(assessmentId, "CONTRACT_BODY", 0), auth, ready, go));
            var award = executor.submit(() -> confirmation(projectId, fileId,
                    "race-award-" + UUID.randomUUID(),
                    confirmationBody(assessmentId, "AWARD_NOTICE", 0), auth, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<MvcResult> results = List.of(contract.get(10, TimeUnit.SECONDS),
                    award.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult winner = results.stream().filter(result -> result.getResponse().getStatus() == 200)
                    .findFirst().orElseThrow();
            String winningDecision = JsonPath.read(winner.getResponse().getContentAsString(), "$.data.decisionId");
            String winningRole = JsonPath.read(winner.getResponse().getContentAsString(), "$.data.selectedRole");
            assertThat(decisions.findLatestByFileId(UUID.fromString(fileId)).orElseThrow()
                    .decisionId().toString()).isEqualTo(winningDecision);
            if ("CONTRACT_BODY".equals(winningRole)) {
                assertThat(candidates.findByProjectId(UUID.fromString(projectId))).singleElement()
                        .satisfies(candidate -> assertThat(candidate.sourceRoleDecisionId().toString())
                                .isEqualTo(winningDecision));
            } else {
                assertThat(candidates.findByProjectId(UUID.fromString(projectId))).isEmpty();
            }
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void legacyDraftRemainsReadableButNeedsHumanIdentityAndReauthorizationBeforePromotion() throws Exception {
        Authentication auth = auth("LEGAL");
        String legacyProject = createProject(auth);
        ContractCandidate legacy = seedLegacyCandidate(legacyProject, ProjectFileCategory.CONTRACT_BODY);
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        legacyProject, legacy.id()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateAuthoritySource").value("LEGACY_COMPAT"))
                .andExpect(jsonPath("$.data.sourceRoleDecisionId").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        legacyProject, legacy.id())
                        .header("Idempotency-Key", "legacy-pre-identity-" + UUID.randomUUID())
                        .contentType("application/json").content(validPromotionBody(0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        assertThat(parses.findLatestByFileId(legacy.sourceContractFileId())).isEmpty();
        String legacyFileId = legacy.sourceContractFileId().toString();
        String roleAssessmentId = assess(legacyProject, legacyFileId, auth);
        assertThat(parses.findLatestByFileId(legacy.sourceContractFileId()).orElseThrow().parseOrigin())
                .isEqualTo(ProjectFileParseOrigin.LEGACY_IMPORTED);
        String identityDecisionId = confirmIdentity(legacyProject, legacyFileId, auth);
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        legacyProject, legacyFileId)
                        .header("Idempotency-Key", "legacy-reauthorize-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(roleAssessmentId, "CONTRACT_BODY", 0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(legacy.id().toString()));
        assertThat(candidates.findByProjectId(UUID.fromString(legacyProject))).singleElement()
                .satisfies(authorized -> {
                    assertThat(authorized.id()).isEqualTo(legacy.id());
                    assertThat(authorized.sourceIdentityDecisionId().toString()).isEqualTo(identityDecisionId);
                    assertThat(authorized.versionNo()).isEqualTo(1);
                });
        confirmAndPromote(legacyProject, legacy.id().toString(), 1, auth);

        String unknownProject = createProject(auth);
        ContractCandidate unknown = seedLegacyCandidate(unknownProject, ProjectFileCategory.UNKNOWN);
        mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        unknownProject, unknown.id())
                        .header("Idempotency-Key", "unknown-promote-" + UUID.randomUUID())
                        .contentType("application/json").content(validPromotionBody(0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
        assertThat(candidates.findByProjectId(UUID.fromString(unknownProject))).singleElement()
                .satisfies(candidate -> assertThat(candidate.promotionState().name()).isEqualTo("DRAFT"));
    }

    @Test
    void confirmationUsesAuthenticatedActorAndSurvivesReloadWithSameDecisionIdentity() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "001.docx", contractText());
        MvcResult assessment = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "confirm-assessment-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = JsonPath.read(assessment.getResponse().getContentAsString(), "$.data.assessmentId");
        String key = "confirmation-" + UUID.randomUUID();
        String body = "{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\","
                + "\"expectedVersion\":0,\"reason\":\"  人工　复核 通过 \","
                + "\"confirmedBy\":\"attacker\"}";

        MvcResult first = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(body.formatted(assessmentId)).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.decidedBy").value("role-api-legal"))
                .andExpect(jsonPath("$.data.reason").value("人工 复核 通过"))
                .andExpect(jsonPath("$.data.effectiveRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.effectiveRoleSource").value("HUMAN_CONFIRMATION"))
                .andReturn();
        String decisionId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.decisionId");

        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(body.formatted(assessmentId)).with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(decisionId))
                .andExpect(jsonPath("$.data.replayed").value(true));

        Map<String, Object> reloaded = fileProjection(projectId, fileId, auth);
        assertThat(reloaded).containsEntry("roleDecisionStatus", "CONFIRMED")
                .containsEntry("effectiveRole", "CONTRACT_BODY")
                .containsEntry("effectiveRoleSource", "HUMAN_CONFIRMATION")
                .containsEntry("roleDecisionId", decisionId)
                .containsEntry("roleDecisionVersion", 1);

        String changed = "{\"assessmentId\":\"%s\",\"selectedRole\":\"OTHER\","
                + "\"expectedVersion\":0,\"reason\":\"人工 复核 通过\"}";
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(changed.formatted(assessmentId)).with(csrf()).with(authentication(auth)))
                .andExpect(status().isConflict());
    }

    @Test
    void assessmentCanBeReopenedByDurableIdentityWithoutCreatingCommandState() throws Exception {
        Authentication auth = auth("AUDITOR");
        String projectId = createProject(auth);
        String fileId = seedParsedFile(projectId, "001.pdf", contractText());
        MvcResult created = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "reopen-assessment-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.assessmentId");
        String parseResultId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.parseResultId");
        String proposedRole = JsonPath.read(created.getResponse().getContentAsString(), "$.data.proposedRole");
        List<Map<String, Object>> evidence = JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.evidence");
        String otherProjectId = createProject(auth);
        UUID latestAssessmentId = assessments.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().assessmentId();
        var latestDecisionBefore = decisions.findLatestByFileId(UUID.fromString(fileId));
        int idempotencyCount = idempotency.size();
        int auditCount = operationAudits.snapshot().size();

        MvcResult reopened = mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments/{assessmentId}",
                        projectId, fileId, assessmentId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentId").value(assessmentId))
                .andExpect(jsonPath("$.data.parseResultId").value(parseResultId))
                .andExpect(jsonPath("$.data.proposedRole").value(proposedRole))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andReturn();
        List<Map<String, Object>> reopenedEvidence = JsonPath.read(
                reopened.getResponse().getContentAsString(), "$.data.evidence");
        assertThat(reopenedEvidence).isEqualTo(evidence);

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments/{assessmentId}",
                        otherProjectId, fileId, assessmentId).with(authentication(auth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        assertThat(assessments.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().assessmentId())
                .isEqualTo(latestAssessmentId);
        assertThat(decisions.findLatestByFileId(UUID.fromString(fileId))).isEqualTo(latestDecisionBefore);
        assertThat(idempotency.size()).isEqualTo(idempotencyCount);
        assertThat(operationAudits.snapshot()).hasSize(auditCount);
    }

    @Test
    void decisionCanBeReopenedByDurableIdentityWithoutChangingLatestRoleOrCommandState() throws Exception {
        Authentication auth = auth("LEGAL");
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "001.docx", contractText());
        String otherFileId = seedParsedFile(projectId, "附件.pdf", "附件材料");
        MvcResult assessment = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "decision-reopen-assessment-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = JsonPath.read(assessment.getResponse().getContentAsString(), "$.data.assessmentId");
        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "decision-reopen-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\"," +
                                "\"expectedVersion\":0,\"reason\":\"reviewed\"}").formatted(assessmentId))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String decisionId = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decisionId");
        String selectedRole = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.selectedRole");
        String decidedBy = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decidedBy");
        Number versionNo = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.versionNo");
        UUID latestAssessmentId = assessments.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().assessmentId();
        UUID latestDecisionId = decisions.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().decisionId();
        int idempotencyCount = idempotency.size();
        int auditCount = operationAudits.snapshot().size();

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, fileId, decisionId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionId").value(decisionId))
                .andExpect(jsonPath("$.data.selectedRole").value(selectedRole))
                .andExpect(jsonPath("$.data.decidedBy").value(decidedBy))
                .andExpect(jsonPath("$.data.versionNo").value(versionNo.intValue()))
                .andExpect(jsonPath("$.data.replayed").value(false));

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-decisions/{decisionId}",
                        projectId, otherFileId, decisionId).with(authentication(auth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        assertThat(assessments.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().assessmentId())
                .isEqualTo(latestAssessmentId);
        assertThat(decisions.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().decisionId())
                .isEqualTo(latestDecisionId);
        assertThat(fileProjection(projectId, fileId, auth)).containsEntry("roleDecisionId", decisionId)
                .containsEntry("roleDecisionStatus", "CONFIRMED")
                .containsEntry("effectiveRole", "CONTRACT_BODY");
        assertThat(idempotency.size()).isEqualTo(idempotencyCount);
        assertThat(operationAudits.snapshot()).hasSize(auditCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "UNKNOWN"})
    void terminalAssessmentReopenDoesNotInventBusinessDecisionStatus(String executionStatus) throws Exception {
        Authentication auth = auth("AUDITOR");
        String projectId = createProject(auth);
        String fileId = seedParsedFile(projectId, "附件.pdf", "synthetic parsed body");
        UUID project = UUID.fromString(projectId);
        UUID file = UUID.fromString(fileId);
        ProjectFile storedFile = files.findByProjectIdAndId(project, file).orElseThrow();
        ProjectFileParseResult parse = parses.findLatestByFileId(file).orElseThrow();
        Instant createdAt = Instant.now().minusSeconds(3);
        ProjectFileRoleAssessment terminal = ProjectFileRoleAssessment.pending(
                        UUID.randomUUID(), project, file, storedFile.sha256(), parse.parseResultId(),
                        parse.parseVersion(), ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC,
                        "role-assessment-v1", "manual-confirmation-v1", sha256("terminal-" + UUID.randomUUID()),
                        null, null, createdAt)
                .start(createdAt.plusSeconds(1))
                .finishWithoutProposal(RoleAssessmentExecutionStatus.valueOf(executionStatus),
                        "SYNTHETIC_TERMINAL_OUTCOME", createdAt.plusSeconds(2));
        assessments.insertIfAbsent(terminal);

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments/{assessmentId}",
                        projectId, fileId, terminal.assessmentId()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionStatus").value(executionStatus))
                .andExpect(jsonPath("$.data.proposedRole").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.decisionStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.evidence").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING"})
    void inFlightAssessmentProjectionDoesNotInventBusinessDecisionStatus(String executionStatus) throws Exception {
        Authentication auth = auth("AUDITOR");
        String projectId = createProject(auth);
        String fileId = seedParsedFile(projectId, "附件.pdf", "synthetic parsed body");
        UUID project = UUID.fromString(projectId);
        UUID file = UUID.fromString(fileId);
        ProjectFile storedFile = files.findByProjectIdAndId(project, file).orElseThrow();
        ProjectFileParseResult parse = parses.findLatestByFileId(file).orElseThrow();
        Instant createdAt = Instant.now().minusSeconds(2);
        ProjectFileRoleAssessment pending = ProjectFileRoleAssessment.pending(
                UUID.randomUUID(), project, file, storedFile.sha256(), parse.parseResultId(), parse.parseVersion(),
                ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC, "role-assessment-v1",
                "manual-confirmation-v1", sha256("in-flight-" + UUID.randomUUID()), null, null, createdAt);
        ProjectFileRoleAssessment assessment = "RUNNING".equals(executionStatus)
                ? pending.start(createdAt.plusSeconds(1)) : pending;
        assessments.insertIfAbsent(assessment);

        assertThat(fileProjection(projectId, fileId, auth))
                .containsEntry("roleExecutionStatus", executionStatus)
                .containsEntry("roleDecisionStatus", null)
                .containsEntry("suggestedRole", null);
    }

    @Test
    void roleCommandsRequireAuthenticationCsrfIdempotencyAndOneOfExistingFourRoles() throws Exception {
        Authentication auditor = auth("AUDITOR");
        String projectId = createProject(auditor);
        String fileId = upload(projectId, auditor, "001.docx", contractText());
        String route = "/api/v1/projects/%s/files/%s/role-assessments".formatted(projectId, fileId);

        mockMvc.perform(post(route).header("Idempotency-Key", "unauthenticated").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(route).header("Idempotency-Key", "missing-csrf")
                        .with(authentication(auditor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(route).with(csrf()).with(authentication(auditor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(route).header("Idempotency-Key", "wrong-role").with(csrf())
                        .with(authentication(auth("PROJECT_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL"})
    void eachExistingAuthorizedRoleReachesTheRoleCommand(String role) throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-assessments",
                        UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "allowed-" + role.toLowerCase(java.util.Locale.ROOT))
                        .with(csrf()).with(authentication(auth(role))))
                .andExpect(status().isNotFound());
    }

    private String createProject(Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "role-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/projects/{projectId}", projectId)
                        .header("Idempotency-Key", "role-project-metadata-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Synthetic equipment project\","
                                + "\"projectNo\":\"SYN-2026-001\"}")
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());
        return projectId;
    }

    private String confirmIdentity(String projectId, String fileId, Authentication auth) throws Exception {
        MvcResult assessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "role-test-identity-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = JsonPath.read(assessed.getResponse().getContentAsString(), "$.data.assessmentId");
        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "role-test-identity-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                                + "\"expectedVersion\":0,\"reason\":\"synthetic ownership\"}")
                                .formatted(assessmentId))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decisionId");
    }

    private String upload(String projectId, Authentication auth, String filename, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(text));
        MvcResult result = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "role-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingestionStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.parseStatus").value("PARSED"))
                .andExpect(jsonPath("$.data.parseResultId").isNotEmpty())
                .andExpect(jsonPath("$.data.roleAssessmentId").isNotEmpty())
                .andExpect(jsonPath("$.data.suggestedRole").value("CONTRACT_BODY"))
                .andExpect(jsonPath("$.data.roleAssessmentSource").value("MACHINE_DETERMINISTIC"))
                .andExpect(jsonPath("$.data.roleDecisionStatus").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.data.effectiveRole").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.effectiveRoleSource").value("NO_AUTHORITATIVE_ROLE"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String seedParsedFile(String projectId, String filename, String text) throws Exception {
        UUID fileId = UUID.randomUUID();
        byte[] bytes = docx(text + "\nfixture-id:" + fileId);
        var staged = storage.stage(new ByteArrayInputStream(bytes), bytes.length + 1L);
        storage.promote(staged, new StorageKey(staged.sha256()));
        UUID project = UUID.fromString(projectId);
        UUID parseId = UUID.randomUUID();
        Instant now = Instant.now();
        ProjectFile file = new ProjectFile(fileId, project, null, null, filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ProjectFileCategory.UNKNOWN, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                staged.sha256(), staged.sha256(), staged.size(), text, null, now.minusSeconds(1), now,
                new ProjectFileParseProvenance(ProjectFileParseMethod.DOCX_TEXT, "synthetic-parser",
                        BigDecimal.ONE, null, 1L));
        files.insert(file);
        parses.insertIfAbsent(new ProjectFileParseResult(parseId, project, fileId, staged.sha256(), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.DOCX_TEXT, "synthetic-parser", "1", text, sha256(text), null, 1L,
                BigDecimal.ONE, null, now.minusSeconds(1), now, 0));
        return fileId.toString();
    }

    private Map<String, Object> fileProjection(String projectId, String fileId, Authentication auth)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> files = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return files.stream().filter(value -> fileId.equals(String.valueOf(value.get("id"))))
                .findFirst().orElseThrow();
    }

    private String assess(String projectId, String fileId, Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "assessment-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.assessmentId");
    }

    private ConfirmedCandidate confirmedCandidate(Authentication auth) throws Exception {
        String projectId = createProject(auth);
        String fileId = upload(projectId, auth, "001.docx", contractText());
        String assessmentId = assess(projectId, fileId, auth);
        confirmIdentity(projectId, fileId, auth);
        MvcResult confirmed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "contract-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationBody(assessmentId, "CONTRACT_BODY", 0))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        return new ConfirmedCandidate(projectId, fileId, assessmentId,
                JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.decisionId"),
                JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data.candidateId"));
    }

    private static String confirmationBody(String assessmentId, String role, long version) {
        return ("{\"assessmentId\":\"%s\",\"selectedRole\":\"%s\","
                + "\"expectedVersion\":%d,\"reason\":\"synthetic review\"}")
                .formatted(assessmentId, role, version);
    }

    private static String validPromotionBody(long version) {
        return ("{\"expectedVersion\":%d,\"contractNo\":\"SYN-%s\","
                + "\"contractName\":\"Synthetic Contract\",\"contractType\":\"GOODS\","
                + "\"partyA\":\"Buyer\",\"partyB\":\"Supplier\","
                + "\"contractAmount\":10.00,\"lineItems\":[]}")
                .formatted(version, UUID.randomUUID());
    }

    private MvcResult confirmAndPromote(String projectId, String candidateId, long baseVersion,
            Authentication auth) throws Exception {
        MvcResult saved = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId)
                        .header("Idempotency-Key", "role-candidate-confirm-" + UUID.randomUUID())
                        .contentType("application/json").content(validPromotionBody(baseVersion))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String confirmationId = JsonPath.read(saved.getResponse().getContentAsString(), "$.data.confirmationId");
        long resultVersion = ((Number) JsonPath.read(saved.getResponse().getContentAsString(),
                "$.data.resultCandidateVersion")).longValue();
        return mockMvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId)
                        .header("Idempotency-Key", "role-candidate-promote-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":%d,\"confirmationId\":\"%s\"}"
                                .formatted(resultVersion, confirmationId))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult confirmation(String projectId, String fileId, String key, String body,
            Authentication auth, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("confirmation race timeout");
        return mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", key).contentType("application/json")
                        .content(body).with(csrf()).with(authentication(auth))).andReturn();
    }

    private ContractCandidate seedLegacyCandidate(String projectId, ProjectFileCategory category) throws Exception {
        UUID project = UUID.fromString(projectId);
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] durableBytes = ("synthetic legacy file " + fileId).getBytes(StandardCharsets.UTF_8);
        var staged = storage.stage(new ByteArrayInputStream(durableBytes), durableBytes.length + 1L);
        storage.promote(staged, new StorageKey(staged.sha256()));
        ProjectFile file = new ProjectFile(fileId, project, null, "legacy/001.pdf", "001.pdf",
                "application/pdf", category, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, staged.sha256(), staged.sha256(), staged.size(),
                "Contract No: LEGACY-1\nContract Amount: 10.00", null, now.minusSeconds(1), now,
                new ProjectFileParseProvenance(ProjectFileParseMethod.PDF_TEXT, "PDFBOX_LEGACY_TEST",
                        null, 1, 1L));
        files.insert(file);
        ContractCandidate candidate = ContractCandidate.draft(UUID.randomUUID(), project, fileId,
                "LEGACY-1", "Legacy Synthetic Contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("10.00"), null, null, null, null, List.of(), now);
        return candidates.insertIfAbsent(candidate);
    }

    private record ConfirmedCandidate(String projectId, String fileId, String assessmentId,
                                      String decisionId, String candidateId) { }

    private static Authentication auth(String role) {
        String normalized = role.toLowerCase(java.util.Locale.ROOT);
        CurrentUser user = new CurrentUser("role-api-" + normalized, "role." + normalized,
                "Role API " + role, Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Stream<Arguments> roleFixtures() {
        return Stream.of(
                Arguments.of("GENERIC_CONTRACT", "001.pdf", "CONTRACT_BODY", contractText()),
                Arguments.of("GENERIC_AWARD", "扫描件.pdf", "AWARD_NOTICE", """
                        中标通知书
                        项目名称：合成测试项目
                        中标人：示例供应商
                        中标金额：人民币壹佰元
                        贵公司已被确定为中标人，请按通知签订合同。
                        """),
                Arguments.of("GENERIC_TENDER", "采购资料.pdf", "TENDER_DOCUMENT", """
                        公开招标文件
                        招标人：示例采购单位
                        第一章 招标公告
                        第二章 投标人须知
                        第三章 评标办法
                        第四章 合同条款及格式
                        """),
                Arguments.of("GENERIC_BID", "响应材料.pdf", "BID_RESPONSE", """
                        投标文件
                        投标人：示例供应商
                        第一章 投标函
                        第二章 投标报价
                        第三章 商务响应
                        第四章 技术响应
                        法定代表人签字并加盖公章。
                        """),
                Arguments.of("AMBIGUOUS", "附件.pdf", "UNKNOWN", "附件\n这里仅列出随文材料。"));
    }

    private static String contractText() {
        return """
                采购合同
                甲方：示例采购单位
                乙方：示例供应商
                第一条 合同价款
                第二条 履行期限
                第三条 违约责任
                双方签字盖章后生效。
                """;
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : text.lines().toList()) document.createParagraph().createRun().setText(line);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700);
                for (String line : text.lines().toList()) {
                    content.showText(line);
                    content.newLineAtOffset(0, -18);
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
