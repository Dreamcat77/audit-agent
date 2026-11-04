package com.fuquan.audit.common.exception;

import org.springframework.http.HttpStatus;

public enum BusinessErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "invalid argument"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "resource conflict"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "payload too large"),
    BUSINESS_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "business validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "external service unavailable");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    BusinessErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
