package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.Objects;

/** Recovery semantics bounded to the Candidate support replacement operation. */
public final class ContractCandidateSupportReplacementException extends BusinessException {
    public enum Classification {
        OUTCOME_UNCERTAIN,
        REQUEST_REJECTED,
        RESULT_SUPERSEDED,
        INTEGRITY_CONFLICT
    }

    private final Classification classification;

    public ContractCandidateSupportReplacementException(BusinessErrorCode code, String message,
            Classification classification) {
        this(code, message, classification, null);
    }

    public ContractCandidateSupportReplacementException(BusinessErrorCode code, String message,
            Classification classification, Throwable cause) {
        super(code, message, cause);
        this.classification = Objects.requireNonNull(classification, "classification must not be null");
    }

    public Classification classification() { return classification; }
}
