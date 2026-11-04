package com.fuquan.audit.contract.api.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContractReviewStepRetryControllerTest.ApiConfiguration.class)
class ContractReviewStepRetryControllerTest {
    @Autowired MockMvc mvc;
    @LocalServerPort int port;
    @MockBean ReviewStepRetryService retries;
    static final UUID PROJECT = UUID.randomUUID(), CONTRACT = UUID.randomUUID(), RUN = UUID.randomUUID(),
            STEP = UUID.randomUUID(), PREVIOUS = UUID.randomUUID();
    static final String PATH = "/api/v1/projects/" + PROJECT + "/contracts/" + CONTRACT
            + "/review-runs/" + RUN + "/steps/" + STEP + "/retries";
    static final String BODY = """
            {"expectedRunVersion":7,"expectedStepVersion":3,"previousAttemptId":"%s",
             "previousAttemptNo":1,"previousAttemptVersion":4,"previousStatus":"FAILED",
             "previousExternalCallState":"NOT_STARTED","reason":"重新审核"}
            """.formatted(PREVIOUS);

    @Test void returns202AndCarriesOnlyExpectedServerResourceIdentities() throws Exception {
        var next = new ContractReviewStepAttempt(UUID.randomUUID(), RUN, STEP, 2, "a".repeat(64),
                ContractReviewStepStatus.PENDING, null, null, null, ReviewStepExternalCallState.NOT_STARTED,
                null, null, null, null, null, null, null, null, null, 0);
        when(retries.retry(any())).thenReturn(new ReviewStepRetryService.RetryResult(next, false));
        mvc.perform(post(PATH).with(user("auditor").roles("AUDITOR")).with(csrf())
                .header("Idempotency-Key", "retry-key").header("X-Request-Id", "worker-retry-request")
                .contentType("application/json").content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.attempt.attemptNo").value(2))
                .andExpect(header().string("X-Request-Id", "worker-retry-request"));
        var captured = ArgumentCaptor.forClass(RetryReviewStepCommand.class);
        verify(retries).retry(captured.capture());
        assertThat(captured.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(captured.getValue().previousAttemptId()).isEqualTo(PREVIOUS);
        assertThat(captured.getValue().idempotencyKey()).isEqualTo("retry-key");
    }

    @Test void authenticationRoleAndCsrfRemainMandatory() throws Exception {
        mvc.perform(post(PATH).with(csrf()).header("Idempotency-Key", "retry-key")
                .contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user("reader").roles("VIEWER")).with(csrf())
                .header("Idempotency-Key", "retry-key").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(user("auditor").roles("AUDITOR"))
                .header("Idempotency-Key", "retry-key").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(retries);
    }

    @Test void forgedActorUnknownFieldsInvalidEnumsAndMissingKeyFailBeforeCommand() throws Exception {
        for (String body : new String[] {BODY.replace("\"reason\":", "\"requestedBy\":\"forged\",\"reason\":"),
                BODY.replace("\"FAILED\"", "\"UNKNOWN_STATUS\""), BODY.replace("\"previousAttemptNo\":1", "\"previousAttemptNo\":0")}) {
            mvc.perform(post(PATH).with(user("auditor").roles("AUDITOR")).with(csrf())
                    .header("Idempotency-Key", "retry-key").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).with(user("auditor").roles("AUDITOR")).with(csrf())
                .contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(retries);
    }

    @Test void realLocalHttpCurlRejectsRetryWithoutSessionAndCsrf() throws Exception {
        var process = new ProcessBuilder("curl", "--silent", "--show-error", "--include", "--max-time", "10",
                "--request", "POST", "http://127.0.0.1:" + port + PATH,
                "--header", "Idempotency-Key: curl-retry-security-test",
                "--header", "X-Request-Id: curl-track-b-security", "--header", "Content-Type: application/json",
                "--data", "{}").redirectErrorStream(true).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(response).contains("HTTP/1.1 403", "\"code\":403", "curl-track-b-security");
        // Keep only non-secret evidence: never persist Set-Cookie/session identity in test output.
        System.out.println("TRACK_B_LOCAL_CURL_RESPONSE=HTTP/1.1 403; X-Request-Id: curl-track-b-security; "
                + response.substring(response.indexOf("{\"code\":403")));
        verifyNoInteractions(retries);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApiConfiguration {
        @Bean ContractReviewStepRetryController retryController(ReviewStepRetryService service) {
            return new ContractReviewStepRetryController(service);
        }
    }
}
