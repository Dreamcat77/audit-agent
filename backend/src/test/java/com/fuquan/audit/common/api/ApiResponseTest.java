package com.fuquan.audit.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void createsSuccessResponse() {
        Map<String, String> data = Map.of("status", "UP");

        ApiResponse<Map<String, String>> response = ApiResponse.success(data);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isEqualTo(data);
    }

    @Test
    void createsFailureResponse() {
        ApiResponse<Void> response = ApiResponse.failure(404, "resource not found");

        assertThat(response.code()).isEqualTo(404);
        assertThat(response.message()).isEqualTo("resource not found");
        assertThat(response.data()).isNull();
    }
}
