package com.fuquan.audit.contract.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.security.domain.CurrentUser;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "audit.file-storage.max-file-size=8B",
        "audit.security.local-account.id=session-api-user",
        "audit.security.local-account.real-name=Session API"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractReviewSessionApiIntegrationTest {
    private static final byte[] PDF = "%PDF-x".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sessionApisRequireAuthenticationRoleAndCsrf() throws Exception {
        mockMvc.perform(get("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID)
                        .with(authentication(auth("VIEWER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID)
                        .header("Idempotency-Key", key("csrf"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"审核\"}")
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createValidationNotFoundAndIdempotentReplayAreExplicit() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"审核\"}")
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID)
                        .header("Idempotency-Key", key("blank"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}")
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/contracts/{id}/review-sessions", UUID.randomUUID())
                        .header("Idempotency-Key", key("missing"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"审核\"}")
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isNotFound());

        String key = key("create-replay");
        MvcResult first = createSession(LocalContractFixtures.PASS_CONTRACT_ID, key, "重放审核");
        MvcResult second = createSession(LocalContractFixtures.PASS_CONTRACT_ID, key, "重放审核");
        assertThat((String) path(first, "$.data.sessionId"))
                .isEqualTo((String) path(second, "$.data.sessionId"));
        assertThat((Boolean) path(second, "$.data.replayed")).isTrue();

        mockMvc.perform(get("/api/v1/contracts/{id}/review-sessions", LocalContractFixtures.PASS_CONTRACT_ID)
                        .with(authentication(auth("LEGAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == '重放审核')]").isNotEmpty());
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}",
                        (String) path(first, "$.data.sessionId"))
                        .with(authentication(auth("PROCUREMENT_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(1))
                .andExpect(jsonPath("$.data.createdBy").value("session-api-user"))
                .andExpect(jsonPath("$.data.createdByName").value("Session API"));
    }

    @Test
    void uploadReplaceHistoryRemoveContentIdorAndReattachAreDurable() throws Exception {
        MvcResult created = createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("create-flow"), "材料流转");
        String sessionId = path(created, "$.data.sessionId");
        MvcResult other = createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("create-other"), "另一个会话");
        String otherSessionId = path(other, "$.data.sessionId");

        MvcResult first = upload(sessionId, "CONTRACT_BODY", 0, key("body-first"), "contract.pdf", PDF);
        String firstId = path(first, "$.data.document.documentId");
        assertThat(((Number) path(first, "$.data.session.versionNo")).longValue()).isEqualTo(2);

        MvcResult replay = upload(sessionId, "CONTRACT_BODY", 0,
                requestKey(first, "body-first"), "contract.pdf", PDF);
        assertThat((String) path(replay, "$.data.document.documentId")).isEqualTo(firstId);
        assertThat((Boolean) path(replay, "$.data.replayed")).isTrue();

        MvcResult second = upload(sessionId, "CONTRACT_BODY", 2, key("body-second"), "next.pdf", PDF);
        String secondId = path(second, "$.data.document.documentId");
        assertThat((String) path(second, "$.data.document.replacesDocumentId")).isEqualTo(firstId);

        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .param("includeHistory", "true").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMaterials.length()").value(1))
                .andExpect(jsonPath("$.data.currentMaterials[0].documentId").value(secondId))
                .andExpect(jsonPath("$.data.history[?(@.documentId == '" + firstId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/contract-review-sessions/{sid}/documents/{did}/content", sessionId, firstId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/contract-review-sessions/{sid}/documents/{did}/content", sessionId, firstId)
                        .param("history", "true").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().longValue("Content-Length", PDF.length))
                .andExpect(content().bytes(PDF));
        mockMvc.perform(get("/api/v1/contract-review-sessions/{sid}/documents/{did}/content", otherSessionId, secondId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/contract-review-sessions/{sid}/documents/{did}", sessionId, secondId)
                        .param("expectedVersion", "4")
                        .header("Idempotency-Key", key("remove"))
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(5));
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .param("includeHistory", "true").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMaterials.length()").value(0))
                .andExpect(jsonPath("$.data.history.length()").value(2));

        MvcResult reattached = upload(sessionId, "CONTRACT_BODY", 5, key("reattach"), "again.pdf", PDF);
        assertThat(((Number) path(reattached, "$.data.session.versionNo")).longValue()).isEqualTo(7);
    }

    @Test
    void confirmRequiresBothCoreMaterialsAndReadyRejectsFurtherMutation() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("confirm-create"), "确认材料"), "$.data.sessionId");
        upload(sessionId, "CONTRACT_BODY", 0, key("confirm-body"), "contract.pdf", PDF);

        mockMvc.perform(post("/api/v1/contract-review-sessions/{id}/confirm-materials", sessionId)
                        .header("Idempotency-Key", key("confirm-missing"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}")
                        .with(csrf()).with(authentication(auth("LEGAL"))))
                .andExpect(status().isUnprocessableEntity());

        upload(sessionId, "AWARD_NOTICE", 2, key("confirm-award"), "award.pdf", PDF);
        String confirmKey = key("confirm-ready");
        mockMvc.perform(post("/api/v1/contract-review-sessions/{id}/confirm-materials", sessionId)
                        .header("Idempotency-Key", confirmKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}")
                        .with(csrf()).with(authentication(auth("LEGAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.versionNo").value(5));
        mockMvc.perform(post("/api/v1/contract-review-sessions/{id}/confirm-materials", sessionId)
                        .header("Idempotency-Key", confirmKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}")
                        .with(csrf()).with(authentication(auth("LEGAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        uploadExpecting(sessionId, "OTHER", 5, key("ready-upload"), "other.pdf", PDF, 409);
    }

    @Test
    void unsupportedOrSpoofedFileIs422AndDoesNotCreateCurrentMaterial() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("invalid-create"), "非法文件"), "$.data.sessionId");
        uploadExpecting(sessionId, "CONTRACT_BODY", 0, key("exe"), "evil.pdf",
                new byte[] {0x4d, 0x5a, 0x00}, 422);
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMaterials.length()").value(0));
    }

    @Test
    void emptyFileIs422AndBusinessFileLimitIs413() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("file-rejection-create"), "文件拒绝分类"),
                "$.data.sessionId");

        uploadExpecting(sessionId, "CONTRACT_BODY", 0, key("empty"), "empty.pdf", new byte[0], 422);
        MvcResult tooLarge = uploadExpecting(
                sessionId, "CONTRACT_BODY", 0, key("too-large"), "large.pdf",
                "%PDF-xxxx".getBytes(StandardCharsets.US_ASCII), 413);

        assertThat(tooLarge.getResponse().getContentAsString())
                .contains("file exceeds configured size limit")
                .doesNotContain("\\storage\\", "/storage/");
    }

    @Test
    void recoveryActionRequiresAuthenticationRoleAndCsrf() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String path = "/api/v1/contract-review-sessions/{sid}/documents/{did}/recover";

        mockMvc.perform(post(path, sessionId, documentId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, sessionId, documentId)
                        .with(csrf()).with(authentication(auth("VIEWER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, sessionId, documentId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentSingletonUploadsHaveOneWinnerAndNoPendingReservation() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("upload-race-create"), "双上传竞争"),
                "$.data.sessionId");

        List<Integer> statuses = concurrently(
                () -> uploadRaw(sessionId, "CONTRACT_BODY", 0, key("upload-race-a"), "a.pdf", PDF),
                () -> uploadRaw(sessionId, "CONTRACT_BODY", 0, key("upload-race-b"), "b.pdf", PDF));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .param("includeHistory", "true").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMaterials.length()").value(1))
                .andExpect(jsonPath("$.data.history[?(@.status == 'UPLOAD_PENDING')]").isEmpty());
    }

    @Test
    void twoConfirmsAndConfirmUploadRaceUseSessionCas() throws Exception {
        String confirmSession = preparedSession("two-confirm");
        List<Integer> confirms = concurrently(
                () -> confirmRaw(confirmSession, 4, key("confirm-race-a")),
                () -> confirmRaw(confirmSession, 4, key("confirm-race-b")));
        assertThat(confirms).containsExactlyInAnyOrder(200, 409);
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}", confirmSession)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));

        String mixedSession = preparedSession("confirm-upload");
        List<Integer> mixed = concurrently(
                () -> confirmRaw(mixedSession, 4, key("mixed-confirm")),
                () -> uploadRaw(mixedSession, "OTHER", 4, key("mixed-upload"), "other.pdf", PDF));
        assertThat(mixed).containsExactlyInAnyOrder(200, 409);
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}", mixedSession)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(5), org.hamcrest.Matchers.is(6))));
    }

    @Test
    void removeAndReplacementRaceHasOneWinnerAndKeepsHistory() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("remove-replace-create"), "移除替换竞争"),
                "$.data.sessionId");
        String originalId = path(upload(
                sessionId, "CONTRACT_BODY", 0, key("remove-replace-body"), "body.pdf", PDF),
                "$.data.document.documentId");

        List<Integer> statuses = concurrently(
                () -> removeRaw(sessionId, originalId, 2, key("remove-race")),
                () -> uploadRaw(sessionId, "CONTRACT_BODY", 2,
                        key("replace-race"), "replacement.pdf", PDF));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .param("includeHistory", "true").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[?(@.documentId == '" + originalId + "')]").isNotEmpty());
    }

    @Test
    void globalReadyQueryUsesProjectionAndExcludesPreparingSessions() throws Exception {
        createSession(LocalContractFixtures.PASS_CONTRACT_ID, key("global-preparing"), "不应进入主表");
        String readyId = preparedSession("global-ready");
        confirmRaw(readyId, 4, key("global-confirm"));

        mockMvc.perform(get("/api/v1/contract-review-sessions")
                        .param("status", "READY")
                        .param("keyword", "global-ready")
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(readyId))
                .andExpect(jsonPath("$.data.items[0].createdBy").value("session-api-user"))
                .andExpect(jsonPath("$.data.items[0].createdByName").value("Session API"))
                .andExpect(jsonPath("$.data.items[0].contract.contractNo").value("LOCAL-PASS-001"))
                .andExpect(jsonPath("$.data.items[0].contract.contractAmount").isNumber())
                .andExpect(jsonPath("$.data.items[0].contract.amountInWords").value("人民币壹佰元整"))
                .andExpect(jsonPath("$.data.items[0].contract.lineItems").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].contract.awardNotice").doesNotExist());
    }

    @Test
    void publicReviewSessionDeleteRouteIsWithdrawnAndCannotCreateATombstone() throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key("delete-route-withdrawn"), "保留任务"),
                "$.data.sessionId");

        mockMvc.perform(delete("/api/v1/contract-review-sessions/{id}", sessionId)
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", key("delete-route-withdrawn-request"))
                        .with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/api/v1/contract-review-sessions/{id}", sessionId)
                        .with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.deletedAt").isEmpty())
                .andExpect(jsonPath("$.data.deletedBy").isEmpty());
    }

    private MvcResult createSession(UUID contractId, String key, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/contracts/{id}/review-sessions", contractId)
                        .header("Idempotency-Key", key)
                        .header(RequestIdFilter.HEADER_NAME, "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"remark\":\"测试\"}")
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceKind").value("LEGACY_REVIEW_DOCUMENT"))
                .andExpect(jsonPath("$.data.projectId").isEmpty())
                .andReturn();
    }

    private MvcResult upload(
            String sessionId, String type, long version, String key, String name, byte[] bytes) throws Exception {
        return uploadExpecting(sessionId, type, version, key, name, bytes, 200);
    }

    private MvcResult uploadExpecting(
            String sessionId, String type, long version, String key, String name, byte[] bytes, int status)
            throws Exception {
        MvcResult result = uploadRaw(sessionId, type, version, key, name, bytes);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        return result;
    }

    private MvcResult uploadRaw(
            String sessionId, String type, long version, String key, String name, byte[] bytes)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "application/pdf", bytes);
        return mockMvc.perform(multipart("/api/v1/contract-review-sessions/{id}/documents", sessionId)
                        .file(file).param("documentType", type).param("expectedVersion", Long.toString(version))
                        .header("Idempotency-Key", key)
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andReturn();
    }

    private MvcResult confirmRaw(String sessionId, long version, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/contract-review-sessions/{id}/confirm-materials", sessionId)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + "}")
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andReturn();
    }

    private MvcResult removeRaw(String sessionId, String documentId, long version, String key) throws Exception {
        return mockMvc.perform(delete(
                        "/api/v1/contract-review-sessions/{sid}/documents/{did}", sessionId, documentId)
                        .param("expectedVersion", Long.toString(version)).header("Idempotency-Key", key)
                        .with(csrf()).with(authentication(auth("AUDITOR"))))
                .andReturn();
    }

    private String preparedSession(String prefix) throws Exception {
        String sessionId = path(createSession(
                LocalContractFixtures.PASS_CONTRACT_ID, key(prefix + "-create"), prefix),
                "$.data.sessionId");
        upload(sessionId, "CONTRACT_BODY", 0, key(prefix + "-body"), "body.pdf", PDF);
        upload(sessionId, "AWARD_NOTICE", 2, key(prefix + "-award"), "award.pdf", PDF);
        return sessionId;
    }

    private List<Integer> concurrently(Callable<MvcResult> firstAction, Callable<MvcResult> secondAction)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return firstAction.call().getResponse().getStatus();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return secondAction.call().getResponse().getStatus();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T path(MvcResult result, String expression) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), expression);
    }

    private String requestKey(MvcResult ignored, String prefix) {
        return lastKeys.get(prefix);
    }

    private final java.util.Map<String, String> lastKeys = new java.util.concurrent.ConcurrentHashMap<>();

    private String key(String prefix) {
        String value = prefix + "-" + UUID.randomUUID();
        lastKeys.put(prefix, value);
        return value;
    }

    private Authentication auth(String role) {
        CurrentUser user = new CurrentUser(
                "session-api-user", "session.api", "Session API", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
