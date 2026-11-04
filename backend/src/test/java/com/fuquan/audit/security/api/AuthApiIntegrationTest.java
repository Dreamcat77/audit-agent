package com.fuquan.audit.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.security.application.LoginRequestMetadata;
import com.fuquan.audit.security.application.port.LoginAuditEvent;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.LoginAuditEventType;
import com.fuquan.audit.security.domain.LoginFailureReason;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.infra.memory.InMemoryLoginAuditAdapter;
import com.fuquan.audit.security.infra.memory.InMemoryUserAccountRepository;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "logging.level.org.springframework=INFO",
        "logging.level.com.fuquan.audit.common.observability.FlowDebugLogger=DEBUG"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InMemoryUserAccountRepository userAccountRepository;

    @Autowired
    private InMemoryLoginAuditAdapter loginAuditAdapter;

    @MockitoSpyBean
    private AuthenticationManager authenticationManager;

    @Test
    void loginMeLogoutFlowUsesCsrfSessionFixationProtectionAndAudit(CapturedOutput output) throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 2, null);
        String sensitiveSessionId = "sensitive-session-" + UUID.randomUUID();
        CsrfExchange initialCsrf = csrf(new MockHttpSession(new MockServletContext(), sensitiveSessionId));
        String originalSessionId = initialCsrf.session().getId();
        String requestId = "auth-flow-request-" + UUID.randomUUID();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .session(initialCsrf.session())
                        .header(initialCsrf.headerName(), initialCsrf.token())
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), rawPassword)))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, requestId))
                .andExpect(jsonPath("$.data.userId").value(account.id()))
                .andExpect(jsonPath("$.data.username").value(account.username()))
                .andExpect(jsonPath("$.data.realName").value(account.realName()))
                .andExpect(jsonPath("$.data.roles[0]").value("AUDITOR"))
                .andReturn();

        String loginBody = login.getResponse().getContentAsString();
        assertThat(loginBody)
                .doesNotContain("password")
                .doesNotContain("passwordHash")
                .doesNotContain("sessionId")
                .doesNotContain("csrfToken");
        assertThat(initialCsrf.session().getId()).isNotEqualTo(originalSessionId);

        mockMvc.perform(get("/api/v1/auth/me").session(initialCsrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(account.id()))
                .andExpect(jsonPath("$.data.username").value(account.username()));

        UserAccount afterSuccess = userAccountRepository.findByUsername(account.username()).orElseThrow();
        assertThat(afterSuccess.failedLoginCount()).isZero();
        assertThat(afterSuccess.lastLoginAt()).isNotNull();

        LoginAuditEvent successAudit = loginAuditAdapter.snapshot().stream()
                .filter(event -> event.username().equals(account.username()))
                .filter(event -> event.eventType() == LoginAuditEventType.LOGIN_SUCCESS)
                .findFirst()
                .orElseThrow();
        assertThat(successAudit.requestId()).isEqualTo(requestId);
        assertThat(successAudit.traceId()).isNotBlank();
        assertThat(successAudit.sourceIp()).isEqualTo("127.0.0.1");

        CsrfExchange logoutCsrf = csrf(initialCsrf.session());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(logoutCsrf.session())
                        .header(logoutCsrf.headerName(), logoutCsrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("logged_out"));
        assertThat(logoutCsrf.session().isInvalid()).isTrue();

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
        assertThat(loginAuditAdapter.snapshot().stream()
                .filter(event -> event.username().equals(account.username()))
                .filter(event -> event.eventType() == LoginAuditEventType.LOGOUT)
                .toList())
                .singleElement()
                .satisfies(event -> assertThat(event.failureReason()).isNull());
        assertThat(output)
                .doesNotContain(rawPassword)
                .doesNotContain(sensitiveSessionId)
                .doesNotContain("sessionId=");
    }

    @Test
    void successfulLoginExcludesRequestMetadataFromRedisSerializableSecurityContext() throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 0, null);
        CsrfExchange loginCsrf = csrf(null);
        String requestId = "redis-session-login-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/login")
                        .session(loginCsrf.session())
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), rawPassword)))
                .andExpect(status().isOk());

        ArgumentCaptor<Authentication> authenticationRequestCaptor =
                ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(authenticationRequestCaptor.capture());
        assertThat(authenticationRequestCaptor.getValue().getDetails())
                .isInstanceOfSatisfying(LoginRequestMetadata.class, metadata -> {
                    assertThat(metadata.requestId()).isEqualTo(requestId);
                    assertThat(metadata.traceId()).isNotBlank();
                    assertThat(metadata.sourceIp()).isEqualTo("127.0.0.1");
                });

        SecurityContext persistedContext = (SecurityContext) loginCsrf.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(persistedContext).isNotNull();
        Authentication persistedAuthentication = persistedContext.getAuthentication();
        assertThat(persistedAuthentication.getDetails()).isNull();
        assertThat(persistedAuthentication.getPrincipal())
                .isInstanceOfSatisfying(CurrentUser.class, currentUser -> {
                    assertThat(currentUser.userId()).isEqualTo(account.id());
                    assertThat(currentUser.username()).isEqualTo(account.username());
                    assertThat(currentUser.roles()).containsExactly("AUDITOR");
                });
        assertThat(persistedAuthentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUDITOR");
        assertThat(Serializable.class.isAssignableFrom(LoginRequestMetadata.class)).isFalse();
        assertThatCode(() -> new JdkSerializationRedisSerializer().serialize(persistedContext))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongPasswordAndUnknownUserReturnSameGeneric401AndAreAudited() throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 0, null);
        String wrongPassword = UUID.randomUUID().toString();
        String missingUsername = "missing-" + UUID.randomUUID();

        MvcResult wrong = loginExpectingUnauthorized(account.username(), wrongPassword);
        MvcResult missing = loginExpectingUnauthorized(missingUsername, wrongPassword);

        assertThat(wrong.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
        assertThat(loginAuditAdapter.snapshot().stream()
                .filter(event -> event.username().equals(account.username()))
                .filter(event -> event.eventType() == LoginAuditEventType.LOGIN_FAILED)
                .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.failureReason()).isEqualTo(LoginFailureReason.BAD_CREDENTIALS);
                    assertThat(event.traceId()).isNotBlank();
                    assertThat(event.requestId()).isNotBlank();
                });
        assertThat(loginAuditAdapter.snapshot()).anyMatch(event ->
                event.username().equals(missingUsername)
                        && event.userId() == null
                        && event.failureReason() == LoginFailureReason.BAD_CREDENTIALS);
    }

    @Test
    void disabledAndLockedAccountsCannotLoginAndDoNotLeakStatus() throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount disabled = seedAccount(UserAccountStatus.DISABLED, rawPassword, 0, null);
        UserAccount locked = seedAccount(
                UserAccountStatus.LOCKED,
                rawPassword,
                5,
                Instant.now().plus(10, ChronoUnit.MINUTES));

        MvcResult disabledResult = loginExpectingUnauthorized(disabled.username(), rawPassword);
        MvcResult lockedResult = loginExpectingUnauthorized(locked.username(), rawPassword);

        assertThat(disabledResult.getResponse().getContentAsString())
                .isEqualTo(lockedResult.getResponse().getContentAsString());
        assertThat(loginAuditAdapter.snapshot()).anyMatch(event ->
                event.username().equals(disabled.username())
                        && event.failureReason() == LoginFailureReason.DISABLED);
        assertThat(loginAuditAdapter.snapshot()).anyMatch(event ->
                event.username().equals(locked.username())
                        && event.failureReason() == LoginFailureReason.LOCKED);
    }

    @Test
    void missingCsrfRejectsLoginBeforeCredentialsOrAuditAreProcessed() throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 0, null);
        int auditCountBefore = loginAuditAdapter.snapshot().size();

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), rawPassword)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("access denied"));

        assertThat(loginAuditAdapter.snapshot()).hasSize(auditCountBefore);
    }

    @Test
    void configuredFailureThresholdLocksAccountAndProducesAccountLockedAudit() throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        String wrongPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 0, null);

        for (int attempt = 0; attempt < 5; attempt++) {
            loginExpectingUnauthorized(account.username(), wrongPassword);
        }

        UserAccount locked = userAccountRepository.findByUsername(account.username()).orElseThrow();
        assertThat(locked.failedLoginCount()).isEqualTo(5);
        assertThat(locked.status()).isEqualTo(UserAccountStatus.LOCKED);
        assertThat(locked.lockedUntil()).isAfter(Instant.now());
        assertThat(loginAuditAdapter.snapshot()).anyMatch(event ->
                event.username().equals(account.username())
                        && event.eventType() == LoginAuditEventType.ACCOUNT_LOCKED
                        && event.failureReason() == LoginFailureReason.LOCKED);

        loginExpectingUnauthorized(account.username(), rawPassword);
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("authentication required"));
    }

    @Test
    void httpOutUsesAuthenticatedRequestUserIdAndKeepsAnonymousRequestsAnonymous(CapturedOutput output)
            throws Exception {
        String rawPassword = UUID.randomUUID().toString();
        UserAccount account = seedAccount(UserAccountStatus.ACTIVE, rawPassword, 0, null);
        CsrfExchange loginCsrf = csrf(null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .session(loginCsrf.session())
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), rawPassword)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").session(loginCsrf.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());

        assertThat(output.getOut())
                .containsPattern("HTTP_OUT method=GET path=/api/v1/auth/me status=200 durationMs=\\d+ userId="
                        + Pattern.quote(account.id()))
                .containsPattern("HTTP_OUT method=GET path=/api/v1/health status=200 durationMs=\\d+ userId=anonymous")
                .doesNotContain(rawPassword);
    }

    private MvcResult loginExpectingUnauthorized(String username, String password) throws Exception {
        CsrfExchange csrf = csrf(null);
        return mockMvc.perform(post("/api/v1/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .header(RequestIdFilter.HEADER_NAME, "login-failure-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andReturn();
    }

    private CsrfExchange csrf(MockHttpSession existingSession) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (existingSession != null) {
            request.session(existingSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        return new CsrfExchange(
                (MockHttpSession) result.getRequest().getSession(false),
                data.path("headerName").asText(),
                data.path("token").asText());
    }

    private UserAccount seedAccount(
            UserAccountStatus status,
            String rawPassword,
            int failedLoginCount,
            Instant lockedUntil) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(),
                "user-" + UUID.randomUUID(),
                passwordEncoder.encode(rawPassword),
                "Integration User",
                status,
                failedLoginCount,
                lockedUntil,
                null,
                now,
                now,
                now,
                0,
                Set.of("AUDITOR"));
        userAccountRepository.seed(account);
        return account;
    }

    private byte[] loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsBytes(Map.of("username", username, "password", password));
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {
    }
}
