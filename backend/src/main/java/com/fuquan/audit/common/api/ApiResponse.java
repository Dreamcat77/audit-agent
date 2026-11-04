package com.fuquan.audit.common.api;

import java.util.Objects;

public record ApiResponse<T>(int code, String message, T data) {

    public ApiResponse {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException("code must be a valid HTTP status code");
        }
        Objects.requireNonNull(message, "message must not be null");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> failure(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
