package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.security.domain.CurrentUser;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractCandidateConfirmationApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired InMemoryIdempotencyAdapter idempotency;
    @Autowired ContractCandidateSupportAuthorityRepository supports;
    @Autowired ObjectMapper json;

    @Test
    void explicitSupportConfirmationKeepsExistingHttpContractAndReplacementFencesOldPromotion() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        String supportId = material(projectId, actor, "AWARD_NOTICE", "fileId");
        replaceSupports(projectId, candidateId, actor, 0, List.of(supportId));
        String path = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations";
        String key = "explicit-confirm-" + UUID.randomUUID();
        String requestBody = body(1, "HUMAN-SUPPORT");
        assertThat(Arrays.stream(ProjectController.ConfirmationRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).containsExactly("expectedVersion", "contractNo",
                        "contractName", "contractType", "partyA", "partyB", "partyBCreditCode", "contractAmount",
                        "amountInWords", "lineItems", "awardNotice");
        var response = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(requestBody).header("Idempotency-Key", key).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultCandidateVersion").value(2))
                .andReturn().getResponse();
        JsonNode data = json.readTree(response.getContentAsString()).path("data");
        assertThat(fields(data)).containsExactlyInAnyOrder("confirmationId", "projectId", "candidateId",
                "baseCandidateVersion", "resultCandidateVersion", "sourceFileId", "sourceParseResultId",
                "sourceRoleDecisionId", "sourceIdentityDecisionId", "payloadFingerprint", "payloadSchemaVersion",
                "payload", "confirmedBy", "confirmedAt", "supersedesConfirmationId", "replayed");
        assertThat(fields(data.path("payload"))).containsExactlyInAnyOrder("contractNo", "contractName", "contractType",
                "partyA", "partyB", "partyBCreditCode", "contractAmount", "amountInWords", "lineItems", "awardNotice");
        assertThat(data.path("payloadSchemaVersion").asInt()).isEqualTo(2);
        assertThat(data.toString()).doesNotContain(supportId, "supportAuthority", "supportFileIds");
        String confirmationId = data.path("confirmationId").asText();

        replaceSupports(projectId, candidateId, actor, 2, List.of());
        mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote", projectId, candidateId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":3,\"confirmationId\":\"%s\"}".formatted(confirmationId))
                        .header("Idempotency-Key", "replaced-promote-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());
        mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(requestBody).header("Idempotency-Key", key).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(confirmationId))
                .andExpect(jsonPath("$.data.resultCandidateVersion").value(2))
                .andExpect(jsonPath("$.data.payload.contractNo").value("HUMAN-SUPPORT"))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}", projectId, candidateId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.versionNo").value(3))
                .andExpect(jsonPath("$.data.candidate.promotionState").value("DRAFT"));
    }

    @Test
    void missingSupportCohortBlocksFreshConfirmationButNotCompletedReplay() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        String supportId = material(projectId, actor, "AWARD_NOTICE", "fileId");
        replaceSupports(projectId, candidateId, actor, 0, List.of(supportId));
        String path = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations";
        String key = "missing-cohort-confirm-" + UUID.randomUUID();
        var first = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body(1, "HUMAN-SUPPORT")).header("Idempotency-Key", key)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String confirmationId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.confirmationId");
        supports.deleteCurrentByProjectIdAndCandidateId(UUID.fromString(projectId), UUID.fromString(candidateId));
        int claimsBefore = idempotency.size();
        mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body(1, "HUMAN-SUPPORT")).header("Idempotency-Key", key)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(confirmationId))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body(2, "FRESH-HUMAN")).header("Idempotency-Key", "fresh-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mvc.perform(get(path + "/latest", projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(confirmationId))
                .andExpect(jsonPath("$.data.resultCandidateVersion").value(2));
        assertThat(idempotency.size()).isEqualTo(claimsBefore);
    }

    private static List<String> fields(JsonNode node) {
        var fields = new ArrayList<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void replaceSupports(String projectId, String candidateId, Authentication actor,
            long version, List<String> ids) throws Exception {
        var body = json.createObjectNode().put("expectedCandidateVersion", version);
        var selected = body.putArray("supportFileIds");
        ids.forEach(selected::add);
        mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements",
                        projectId, candidateId).contentType("application/json").content(body.toString())
                        .header("Idempotency-Key", "confirmation-support-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateVersion").value(version + 1));
    }

    @Test
    void postThenReopenByIdAndLatestWithoutReadSideWrites() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor);
        String candidateId = candidate(projectId, actor);
        String path = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations";
        String body = body(0, "  Ｈ－１ ");
        var posted = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body).header("Idempotency-Key", "confirm-api-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCandidateVersion").value(1))
                .andExpect(jsonPath("$.data.payload.contractNo").value("H-1"))
                .andReturn();
        String id = JsonPath.read(posted.getResponse().getContentAsString(), "$.data.confirmationId");
        String parseId = JsonPath.read(posted.getResponse().getContentAsString(), "$.data.sourceParseResultId");
        int beforeReads = idempotency.size();
        mvc.perform(get(path + "/{confirmationId}", projectId, candidateId, id)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(id))
                .andExpect(jsonPath("$.data.sourceParseResultId").value(parseId))
                .andExpect(jsonPath("$.data.payload.contractNo").value("H-1"))
                .andExpect(jsonPath("$.data.replayed").value(false));
        mvc.perform(get(path + "/latest", projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(id));
        assertThat(idempotency.size()).isEqualTo(beforeReads);
        mvc.perform(get(path + "/{confirmationId}", UUID.randomUUID(), candidateId, id)
                        .with(authentication(actor)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameConfirmationCommandReplaysAndWrongCandidateDoesNotLeakOwnership() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        String key = "confirm-replay-" + UUID.randomUUID();
        String path = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations";
        String body = body(0, "H-2");
        var first = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body).header("Idempotency-Key", key).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.data.confirmationId");
        mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body).header("Idempotency-Key", key).with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(id))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(get(path + "/{confirmationId}", projectId, UUID.randomUUID(), id)
                        .with(authentication(actor)))
                .andExpect(status().isNotFound());
    }

    @Test
    void promotionAcceptsOnlyDurableConfirmationIdentityAndUsesItsHumanValues() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        var confirmed = mvc.perform(post(
                        "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId).contentType("application/json")
                        .content(body(0, "HUMAN-ONLY-1"))
                        .header("Idempotency-Key", "confirm-promote-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String confirmationId = JsonPath.read(confirmed.getResponse().getContentAsString(),
                "$.data.confirmationId");
        mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId).contentType("application/json")
                        .content(("{\"expectedVersion\":1,\"confirmationId\":\"%s\"}")
                                .formatted(confirmationId))
                        .header("Idempotency-Key", "promote-confirmed-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractNo").value("HUMAN-ONLY-1"));
        mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.promotedConfirmationId").value(confirmationId))
                .andExpect(jsonPath("$.data.promotionProvenanceSource").value("DURABLE_CONFIRMATION"))
                .andExpect(jsonPath("$.data.candidate.contractNo").value("EXTRACTED-1"));
    }

    @Test
    void unsavedPromotionPayloadIsNotASecondWriteAuthority() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId).contentType("application/json")
                        .content(body(0, "TEMPORARY-UNSAVED"))
                        .header("Idempotency-Key", "legacy-payload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void omittedExpectedVersionCannotConfirmAZeroVersionCandidate() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        int claimsBefore = idempotency.size();
        mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId).contentType("application/json")
                        .content(body(0, "MISSING-VERSION").replace("\"expectedVersion\":0,", ""))
                        .header("Idempotency-Key", "confirm-missing-version-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                        projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.versionNo").value(0));
        mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations/latest",
                        projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isNotFound());
        assertThat(idempotency.size()).isEqualTo(claimsBefore);
    }

    @Test
    void correctedC2SupersedesC1AndOnlyCurrentConfirmationCanPromote() throws Exception {
        Authentication actor = auth();
        String projectId = project(actor), candidateId = candidate(projectId, actor);
        String path = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations";
        var first = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body(0, "C1"))
                        .header("Idempotency-Key", "correction-c1-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn();
        String c1 = JsonPath.read(first.getResponse().getContentAsString(), "$.data.confirmationId");
        var second = mvc.perform(post(path, projectId, candidateId).contentType("application/json")
                        .content(body(1, "C2"))
                        .header("Idempotency-Key", "correction-c2-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supersedesConfirmationId").value(c1))
                .andExpect(jsonPath("$.data.resultCandidateVersion").value(2))
                .andReturn();
        String c2 = JsonPath.read(second.getResponse().getContentAsString(), "$.data.confirmationId");
        mvc.perform(get(path + "/latest", projectId, candidateId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationId").value(c2));
        String promotion = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote";
        mvc.perform(post(promotion, projectId, candidateId).contentType("application/json")
                        .content("{\"expectedVersion\":2,\"confirmationId\":\"%s\"}".formatted(c1))
                        .header("Idempotency-Key", "stale-c1-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());
        mvc.perform(post(promotion, projectId, candidateId).contentType("application/json")
                        .content("{\"expectedVersion\":2,\"confirmationId\":\"%s\"}".formatted(c2))
                        .header("Idempotency-Key", "current-c2-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contractNo").value("C2"));
    }

    private String project(Authentication actor) throws Exception {
        var created = mvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "confirm-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(patch("/api/v1/projects/{projectId}", id).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Synthetic Project\",\"projectNo\":\"SYN-1\"}")
                        .header("Idempotency-Key", "confirm-metadata-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        return id;
    }

    private String candidate(String projectId, Authentication actor) throws Exception {
        return material(projectId, actor, "CONTRACT_BODY", "candidateId");
    }

    private String material(String projectId, Authentication actor, String role, String resultField) throws Exception {
        var uploaded = mvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", "001.pdf", "application/pdf", pdf(role)))
                        .header("Idempotency-Key", "confirm-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String file = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        var assessedIdentity = mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, file)
                        .header("Idempotency-Key", "confirm-identity-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String identityAssessment = JsonPath.read(assessedIdentity.getResponse().getContentAsString(),
                "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, file)
                        .header("Idempotency-Key", "confirm-identity-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                                + "\"expectedVersion\":0,\"reason\":\"synthetic\"}").formatted(identityAssessment))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        var assessedRole = mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, file)
                        .header("Idempotency-Key", "confirm-role-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String roleAssessment = JsonPath.read(assessedRole.getResponse().getContentAsString(), "$.data.assessmentId");
        var confirmedRole = mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, file)
                        .header("Idempotency-Key", "confirm-role-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"selectedRole\":\"%s\","
                                + "\"expectedVersion\":0,\"reason\":\"synthetic\"}").formatted(roleAssessment, role))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        return JsonPath.read(confirmedRole.getResponse().getContentAsString(), "$.data." + resultField);
    }

    private static String body(int version, String no) {
        return ("{\"expectedVersion\":%d,\"contractNo\":\"%s\",\"contractName\":\"Human Contract\","
                + "\"contractType\":\"GOODS\",\"partyA\":\"Buyer\",\"partyB\":\"Supplier\","
                + "\"contractAmount\":100.00,\"lineItems\":[]}").formatted(version, no);
    }

    private static Authentication auth() {
        CurrentUser user = new CurrentUser("confirm-user", "confirm.user", "Confirm User", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }

    private static byte[] pdf(String role) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(doc, doc.getPage(0))) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12); stream.newLineAtOffset(40, 700);
                for (String line : List.of("Contract No: EXTRACTED-1", "Contract Name: Extracted Contract",
                        "Contract Amount: 100.00", "Material: " + role)) {
                    stream.showText(line); stream.newLineAtOffset(0, -16);
                }
                stream.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); doc.save(output); return output.toByteArray();
        }
    }
}
