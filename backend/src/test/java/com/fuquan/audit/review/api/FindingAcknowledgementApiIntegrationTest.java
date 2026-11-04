package com.fuquan.audit.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.review.infra.memory.InMemoryAuditTaskRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.infra.memory.InMemoryUserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FindingAcknowledgementApiIntegrationTest {

    private static final String POST_PATH =
            "/api/v1/audit-check-results/{checkResultId}/acknowledgement";
    private static final String GET_PATH =
            "/api/v1/audit-tasks/{taskId}/finding-acknowledgements";
    private static final Instant FIXTURE_TIME = Instant.parse("2026-08-25T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryAuditTaskRepository taskRepository;

    @Autowired
    private InMemoryAuditCheckResultRepository resultRepository;

    @Autowired
    private InMemoryUserAccountRepository userAccountRepository;

    @Autowired
    private InMemoryOperationAuditAdapter operationAuditAdapter;

    @Autowired
    private InMemoryIdempotencyAdapter idempotencyAdapter;

    @Test
    void authenticatedEmptyBodyCreatesAcknowledgementWithExactSafeResponseFields() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 9);
        String actorId = "actor-string-" + UUID.randomUUID();
        String requestId = "ack-api-" + UUID.randomUUID();
        seedAccount(actorId, "Current Display Name");

        MvcResult result = acknowledge(
                fixture.resultId(), "create-" + UUID.randomUUID(), requestId, actorId, "AUDITOR");

        Map<String, Object> response = response(result);
        assertThat(response.keySet()).containsExactly("code", "message", "data");
        Map<String, Object> data = nestedMap(response, "data");
        assertThat(data.keySet()).containsExactly(
                "id", "checkResultId", "acknowledgedBy", "acknowledgedByDisplayName",
                "acknowledgedAt", "requestId", "traceId", "ruleVersion");
        assertThat(data)
                .containsEntry("checkResultId", fixture.resultId().toString())
                .containsEntry("acknowledgedBy", actorId)
                .containsEntry("acknowledgedByDisplayName", "Current Display Name")
                .containsEntry("requestId", requestId)
                .containsEntry("ruleVersion", 9);
        assertThat(data.get("id").toString()).isNotBlank();
        assertThat(data.get("acknowledgedAt").toString()).isNotBlank();
        assertThat(data.get("traceId").toString()).matches("[0-9a-f]{32}");
    }

    @Test
    void unexpectedBusinessBodyIsRejectedWithoutCreatingAFact() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 1);
        String actorId = "body-actor-" + UUID.randomUUID();

        mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", "unexpected-body-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}")
                        .with(csrf())
                        .with(authentication(auth(actorId, "AUDITOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("constraint validation failed"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get(GET_PATH, fixture.taskId())
                        .with(authentication(auth(actorId, "AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void sameKeyReplayReturnsTheOriginalAcknowledgement() throws Exception {
        Fixture fixture = fixture(CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW, 2);
        String actorId = "replay-actor-" + UUID.randomUUID();
        String key = "replay-" + UUID.randomUUID();

        Map<String, Object> first = nestedMap(response(acknowledge(
                fixture.resultId(), key, "request-first-" + UUID.randomUUID(), actorId, "LEGAL")), "data");
        Map<String, Object> replay = nestedMap(response(acknowledge(
                fixture.resultId(), key, "request-second-" + UUID.randomUUID(), actorId, "LEGAL")), "data");

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void missingAndBlankIdempotencyKeysAreBadRequests() throws Exception {
        Fixture missing = fixture(CheckStatus.FAIL, RiskLevel.RED, 3);
        Authentication actor = auth("header-actor-" + UUID.randomUUID(), "AUDITOR");

        mockMvc.perform(post(POST_PATH, missing.resultId())
                        .with(csrf())
                        .with(authentication(actor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post(POST_PATH, missing.resultId())
                        .header("Idempotency-Key", "   ")
                        .with(csrf())
                        .with(authentication(actor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void overlongIdempotencyKeyIsRejectedBeforeAnyWrite() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 3);
        Authentication actor = auth("overlong-key-actor-" + UUID.randomUUID(), "AUDITOR");
        int auditsBeforeRequest = operationAuditAdapter.snapshot().size();
        int idempotencyBeforeRequest = idempotencyAdapter.size();

        mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", "k".repeat(129))
                        .with(csrf())
                        .with(authentication(actor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("constraint validation failed"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get(GET_PATH, fixture.taskId())
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(operationAuditAdapter.snapshot()).hasSize(auditsBeforeRequest);
        assertThat(idempotencyAdapter.size()).isEqualTo(idempotencyBeforeRequest);
    }

    @Test
    void anonymousAndDisallowedRolesAreRejected() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 4);

        mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", "anonymous-" + UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get(GET_PATH, fixture.taskId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        Authentication viewer = auth("viewer-" + UUID.randomUUID(), "VIEWER");
        mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", "viewer-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get(GET_PATH, fixture.taskId())
                        .with(authentication(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL"})
    void exactAllowedRoleSetCanReadAndCreate(String role) throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 5);
        String actorId = "allowed-" + role + "-" + UUID.randomUUID();

        acknowledge(fixture.resultId(), "allowed-" + UUID.randomUUID(),
                "allowed-request-" + UUID.randomUUID(), actorId, role);
        mockMvc.perform(get(GET_PATH, fixture.taskId())
                        .with(authentication(auth(actorId, role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].acknowledgedBy").value(actorId));
    }

    @Test
    void csrfIsRequiredForPostButNotGet() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 6);
        Authentication actor = auth("csrf-actor-" + UUID.randomUUID(), "AUDITOR");

        mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", "missing-csrf-" + UUID.randomUUID())
                        .with(authentication(actor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get(GET_PATH, fixture.taskId())
                        .with(authentication(actor)))
                .andExpect(status().isOk());
    }

    @Test
    void passGreenFindingIsUnprocessableAndSafe() throws Exception {
        String hostile = "raw token Cookie /secret/storage/path";
        Fixture fixture = fixture(CheckStatus.PASS, RiskLevel.GREEN, 7, hostile);
        String key = "pass-green-secret-key-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(POST_PATH, fixture.resultId())
                        .header("Idempotency-Key", key)
                        .with(csrf())
                        .with(authentication(auth("pass-actor-" + UUID.randomUUID(), "AUDITOR"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(hostile, key, "Cookie", "/secret/storage/path");
    }

    @Test
    void missingResultAndResultWithMissingParentTaskAreIdorSafe404() throws Exception {
        Authentication actor = auth("missing-actor-" + UUID.randomUUID(), "AUDITOR");

        mockMvc.perform(post(POST_PATH, UUID.randomUUID())
                        .header("Idempotency-Key", "missing-result-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(actor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("resource not found"));

        UUID missingTaskId = UUID.randomUUID();
        UUID orphanResultId = UUID.randomUUID();
        resultRepository.appendAll(List.of(result(
                orphanResultId, missingTaskId, CheckStatus.FAIL, RiskLevel.RED, 8, "orphan detail")));
        mockMvc.perform(post(POST_PATH, orphanResultId)
                        .header("Idempotency-Key", "missing-parent-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(actor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("resource not found"));
    }

    @Test
    void changedFingerprintAndAlreadyAcknowledgedAreConflictsWithSafeBodies() throws Exception {
        Fixture first = fixture(CheckStatus.FAIL, RiskLevel.RED, 10, "database exception token /private/path");
        Fixture second = fixture(CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW, 11);
        String actorId = "conflict-actor-" + UUID.randomUUID();
        String reusedKey = "do-not-expose-key-" + UUID.randomUUID();
        acknowledge(first.resultId(), reusedKey, "conflict-first-" + UUID.randomUUID(), actorId, "AUDITOR");

        MvcResult fingerprint = mockMvc.perform(post(POST_PATH, second.resultId())
                        .header("Idempotency-Key", reusedKey)
                        .with(csrf())
                        .with(authentication(auth(actorId, "AUDITOR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        MvcResult already = mockMvc.perform(post(POST_PATH, first.resultId())
                        .header("Idempotency-Key", "another-secret-key-" + UUID.randomUUID())
                        .with(csrf())
                        .with(authentication(auth(actorId, "AUDITOR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();

        assertThat(fingerprint.getResponse().getContentAsString())
                .doesNotContain(reusedKey, "database exception", "token", "/private/path");
        assertThat(already.getResponse().getContentAsString())
                .doesNotContain("another-secret-key", "database exception", "token", "/private/path");
    }

    @Test
    void getKeepsStableOrderingResolvesNamesLiveAndUsesDeletedUserFallback() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID firstResultId = UUID.randomUUID();
        UUID secondResultId = UUID.randomUUID();
        taskRepository.create(task(taskId));
        resultRepository.appendAll(List.of(
                result(firstResultId, taskId, CheckStatus.FAIL, RiskLevel.RED, 12, "first detail"),
                result(secondResultId, taskId, CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW, 13,
                        "second detail")));
        String liveActor = "live-name-actor-" + UUID.randomUUID();
        String deletedActor = "deleted-actor-" + UUID.randomUUID();
        seedAccount(liveActor, "Before Rename");
        Map<String, Object> firstCreated = nestedMap(response(acknowledge(
                firstResultId, "ordered-first-" + UUID.randomUUID(),
                "ordered-request-1-" + UUID.randomUUID(), liveActor, "AUDITOR")), "data");
        Map<String, Object> secondCreated = nestedMap(response(acknowledge(
                secondResultId, "ordered-second-" + UUID.randomUUID(),
                "ordered-request-2-" + UUID.randomUUID(), deletedActor, "AUDITOR")), "data");
        seedAccount(liveActor, "After Rename");
        int auditsBeforeRead = operationAuditAdapter.snapshot().size();
        int idempotencyBeforeRead = idempotencyAdapter.size();

        List<Map<String, Object>> firstRead = dataList(mockMvc.perform(get(GET_PATH, taskId)
                        .with(authentication(auth(liveActor, "LEGAL"))))
                .andExpect(status().isOk())
                .andReturn());
        List<Map<String, Object>> secondRead = dataList(mockMvc.perform(get(GET_PATH, taskId)
                        .with(authentication(auth(liveActor, "LEGAL"))))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(firstRead).isEqualTo(secondRead);
        List<Object> expectedResultOrder = List.of(firstCreated, secondCreated).stream()
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> Instant.parse(item.get("acknowledgedAt").toString()))
                        .thenComparing(item -> item.get("id").toString()))
                .map(item -> item.get("checkResultId"))
                .toList();
        assertThat(firstRead).extracting(item -> item.get("checkResultId"))
                .containsExactlyElementsOf(expectedResultOrder);
        Map<String, Object> liveView = firstRead.stream()
                .filter(item -> liveActor.equals(item.get("acknowledgedBy")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> deletedView = firstRead.stream()
                .filter(item -> deletedActor.equals(item.get("acknowledgedBy")))
                .findFirst()
                .orElseThrow();
        assertThat(liveView)
                .containsEntry("acknowledgedBy", liveActor)
                .containsEntry("acknowledgedByDisplayName", "After Rename");
        assertThat(deletedView)
                .containsEntry("acknowledgedBy", deletedActor)
                .containsEntry("acknowledgedByDisplayName", "未知或已删除用户");
        assertThat(operationAuditAdapter.snapshot()).hasSize(auditsBeforeRead);
        assertThat(idempotencyAdapter.size()).isEqualTo(idempotencyBeforeRead);
    }

    @Test
    void immutableRouteSurfaceHasNoMutationRevokeOrBulkEndpoints() throws Exception {
        Fixture fixture = fixture(CheckStatus.FAIL, RiskLevel.RED, 14);
        Authentication actor = auth("surface-actor-" + UUID.randomUUID(), "AUDITOR");

        mockMvc.perform(put(POST_PATH, fixture.resultId()).with(csrf()).with(authentication(actor)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(POST_PATH, fixture.resultId()).with(csrf()).with(authentication(actor)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(POST_PATH, fixture.resultId()).with(csrf()).with(authentication(actor)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(POST_PATH + "/revoke", fixture.resultId())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/audit-check-results/acknowledgements")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(GET_PATH, fixture.taskId())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isMethodNotAllowed());
    }

    private Fixture fixture(CheckStatus status, RiskLevel risk, long ruleVersion) {
        return fixture(status, risk, ruleVersion, "safe detail");
    }

    private Fixture fixture(CheckStatus status, RiskLevel risk, long ruleVersion, String detail) {
        UUID taskId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        taskRepository.create(task(taskId));
        resultRepository.appendAll(List.of(result(resultId, taskId, status, risk, ruleVersion, detail)));
        return new Fixture(taskId, resultId);
    }

    private AuditTask task(UUID taskId) {
        return new AuditTask(
                taskId,
                "CONTRACT",
                UUID.randomUUID(),
                1,
                "F02_DETERMINISTIC",
                AuditTaskStatus.DONE,
                "fixture-creator",
                "fixture-request",
                "fixture-trace",
                FIXTURE_TIME.minusSeconds(30),
                FIXTURE_TIME.minusSeconds(20),
                FIXTURE_TIME.minusSeconds(10),
                2);
    }

    private AuditCheckResult result(
            UUID resultId,
            UUID taskId,
            CheckStatus status,
            RiskLevel risk,
            long ruleVersion,
            String detail) {
        return new AuditCheckResult(
                resultId,
                taskId,
                "F02_API_TEST",
                "item-" + resultId,
                status,
                risk,
                risk == RiskLevel.RED,
                "actual-value",
                "expected-value",
                detail,
                ruleVersion,
                FIXTURE_TIME);
    }

    private MvcResult acknowledge(
            UUID resultId,
            String key,
            String requestId,
            String actorId,
            String role) throws Exception {
        return mockMvc.perform(post(POST_PATH, resultId)
                        .header("Idempotency-Key", key)
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .with(csrf())
                        .with(authentication(auth(actorId, role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andReturn();
    }

    private Authentication auth(String actorId, String role) {
        CurrentUser currentUser = new CurrentUser(
                actorId, "username-" + actorId, "Session " + actorId, Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(
                currentUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private void seedAccount(String actorId, String displayName) {
        Instant now = Instant.now();
        userAccountRepository.seed(new UserAccount(
                actorId,
                "account-" + actorId,
                "{noop}not-used",
                displayName,
                UserAccountStatus.ACTIVE,
                0,
                null,
                null,
                now,
                now,
                now,
                0,
                Set.of("AUDITOR")));
    }

    private Map<String, Object> response(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private List<Map<String, Object>> dataList(MvcResult result) throws Exception {
        Map<String, Object> response = response(result);
        Object data = response.get("data");
        assertThat(data).isInstanceOf(List.class);
        List<?> raw = (List<?>) data;
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Object item : raw) {
            assertThat(item).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> acknowledgement = (Map<String, Object>) item;
            mapped.add(acknowledgement);
        }
        return mapped;
    }

    private record Fixture(UUID taskId, UUID resultId) {
    }
}
