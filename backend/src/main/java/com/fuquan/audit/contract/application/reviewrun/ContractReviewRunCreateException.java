package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.Objects;

/** Endpoint-bounded failure semantics for a durable ReviewRun create operation. */
public final class ContractReviewRunCreateException extends BusinessException {
    public enum Classification {
        OUTCOME_UNCERTAIN,
        READINESS_REJECTED,
        INTEGRITY_CONFLICT
    }

    private final Classification classification;

    public ContractReviewRunCreateException(BusinessErrorCode errorCode, String message,
            Classification classification) {
        this(errorCode, message, classification, null);
    }

    public ContractReviewRunCreateException(BusinessErrorCode errorCode, String message,
            Classification classification, Throwable cause) {
        super(errorCode, message, cause);
        this.classification = Objects.requireNonNull(classification,
                "classification must not be null");
    }

    public Classification classification() {
        return classification;
    }
}
