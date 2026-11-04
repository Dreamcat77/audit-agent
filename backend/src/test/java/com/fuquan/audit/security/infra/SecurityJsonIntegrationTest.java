package com.fuquan.audit.security.infra;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SecurityJsonIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousDeniedRequestReturnsUnifiedJson401() throws Exception {
        mockMvc.perform(get("/api/v1/not-public"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @WithMockUser
    void authenticatedMissingRequestReturnsUnifiedJson404() throws Exception {
        mockMvc.perform(get("/api/v1/not-public"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("resource not found"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
