package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.Objects;

/** Error classifications are local to the progression resume endpoint. */
public final class ProjectFileProgressionRecoveryException extends BusinessException {
    public enum Classification {
        RECOVERY_INPUT_CHANGED,
        RECOVERY_ACTION_NOT_ALLOWED,
        RECOVERY_IN_PROGRESS,
        RECOVERY_OUTCOME_UNCERTAIN,
        FACT_SEGMENT_BUDGET_EXCEEDED,
        RECOVERY_IDENTITY_UNPROVEN,
        IDEMPOTENCY_REQUEST_MISMATCH,
        REQUEST_REJECTED
    }

    private final Classification classification;

    public ProjectFileProgressionRecoveryException(BusinessErrorCode code, String message,
            Classification classification) {
        this(code, message, classification, null);
    }

    public ProjectFileProgressionRecoveryException(BusinessErrorCode code, String message,
            Classification classification, Throwable cause) {
        super(code, message, cause);
        this.classification = Objects.requireNonNull(classification, "classification must not be null");
    }

    public Classification classification() {
        return classification;
    }
}
