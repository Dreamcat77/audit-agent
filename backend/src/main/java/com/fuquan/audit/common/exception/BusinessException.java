package com.fuquan.audit.common.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final BusinessErrorCode errorCode;

    public BusinessException(BusinessErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(BusinessErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(BusinessErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public BusinessErrorCode errorCode() {
        return errorCode;
    }
}
