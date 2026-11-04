package com.fuquan.audit.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.common.web.RequestIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HealthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiHealthReturnsUnifiedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.service").value("audit-backend"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void actuatorHealthReturnsUpWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void generatesAndReturnsRequestIdWhenHeaderIsAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andReturn();

        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void passesThroughAndReturnsClientRequestId() throws Exception {
        String requestId = "client-request-20260814";

        mockMvc.perform(get("/api/v1/health")
                        .header(RequestIdFilter.HEADER_NAME, requestId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, requestId));

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
