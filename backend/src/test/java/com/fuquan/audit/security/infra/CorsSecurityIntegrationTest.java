package com.fuquan.audit.security.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.config.CorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties =
        "audit.security.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CorsSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiSecurityConfiguration securityConfiguration;

    @Test
    void configuredLocalOriginGetsCredentialedCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void configuredLocalOriginPreflightAllowsSecurityAndIdempotencyHeaders() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header(
                                "Access-Control-Request-Headers",
                                "Content-Type, Idempotency-Key, X-CSRF-TOKEN, X-Request-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Allow-Headers"))
                .containsIgnoringCase("Content-Type")
                .containsIgnoringCase("Idempotency-Key")
                .containsIgnoringCase("X-CSRF-TOKEN")
                .containsIgnoringCase("X-Request-Id");
    }

    @Test
    void configuredLoopbackOriginGetsCredentialedCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header(
                                "Access-Control-Request-Headers",
                                "Content-Type, X-CSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "http://127.0.0.1:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void configuredOriginAllowsBlacklistPutPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/blacklist/alerts/alert-id/process")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT")
                        .header(
                                "Access-Control-Request-Headers",
                                "Content-Type, X-CSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void configuredOriginAllowsCredentialedProjectPatchPreflight() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/projects/00000000-0000-0000-0000-000000000001")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Content-Type, Idempotency-Key, X-CSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods")).contains("PATCH");
    }

    @Test
    void unconfiguredOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header("Origin", "https://untrusted.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardOriginIsRejectedAtConfigurationBoundary() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("*"));

        assertThatThrownBy(() -> securityConfiguration.corsConfigurationSource(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard CORS origin");
    }
}
