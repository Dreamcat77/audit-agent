package com.fuquan.audit.common.exception;

import java.util.Objects;

public final class ExternalServiceException extends BusinessException {

    private final ExternalServiceType serviceType;

    public ExternalServiceException(ExternalServiceType serviceType, Throwable cause) {
        super(
                BusinessErrorCode.EXTERNAL_SERVICE_ERROR,
                BusinessErrorCode.EXTERNAL_SERVICE_ERROR.defaultMessage(),
                Objects.requireNonNull(cause, "cause must not be null"));
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType must not be null");
    }

    public ExternalServiceType serviceType() {
        return serviceType;
    }
}
