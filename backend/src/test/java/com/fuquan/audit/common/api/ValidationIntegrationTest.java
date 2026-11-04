package com.fuquan.audit.common.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.common.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ValidationIntegrationTest.TestValidationProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@Import({
        GlobalExceptionHandler.class,
        ValidationIntegrationTest.TestValidationProbeController.class
})
class ValidationIntegrationTest {

    private static final String TEST_PATH = "/test-only/validation-probe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notBlankAndNotNullErrorsUseUnifiedResponse() throws Exception {
        mockMvc.perform(post(TEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"sequence\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("validation failed"))
                .andExpect(jsonPath("$.data.name").value("name must not be blank"))
                .andExpect(jsonPath("$.data.sequence").value("sequence must not be null"));
    }

    @Test
    void validRequestUsesUnifiedSuccessResponse() throws Exception {
        mockMvc.perform(post(TEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"baseline\",\"sequence\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("baseline"))
                .andExpect(jsonPath("$.data.sequence").value(1));
    }

    @Test
    void malformedJsonUsesStableUnifiedBadRequestResponse() throws Exception {
        mockMvc.perform(post(TEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("malformed request body"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void emptyBodyConstraintAcceptsNoBytes() throws Exception {
        mockMvc.perform(post(TEST_PATH + "/empty-body"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("accepted"));
    }

    @Test
    void emptyBodyConstraintRejectsUnexpectedBusinessBytes() throws Exception {
        mockMvc.perform(post(TEST_PATH + "/empty-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("constraint validation failed"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @RestController
    @Validated
    @RequestMapping(TEST_PATH)
    public static class TestValidationProbeController {

        @PostMapping
        public ApiResponse<Map<String, Object>> validate(
                @Valid @RequestBody TestValidationProbeRequest request) {
            return ApiResponse.success(Map.of(
                    "name", request.name(),
                    "sequence", request.sequence()));
        }

        @PostMapping("/empty-body")
        public ApiResponse<String> requireEmptyBody(
                @RequestBody(required = false)
                @Size(max = 0, message = "request body must be empty") byte[] body) {
            return ApiResponse.success("accepted");
        }
    }

    public record TestValidationProbeRequest(
            @NotBlank(message = "name must not be blank") String name,
            @NotNull(message = "sequence must not be null") Integer sequence) {
    }
}
