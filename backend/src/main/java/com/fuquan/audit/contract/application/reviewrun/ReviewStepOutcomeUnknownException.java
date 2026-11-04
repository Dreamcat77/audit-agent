package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import java.util.List;

/** Executor signal used only when a durable external REQUESTED outcome cannot be proven. */
public final class ReviewStepOutcomeUnknownException extends RuntimeException {
    private final String safeReasonCode;
    private final List<ContractModelCallAudit> modelCalls;

    public ReviewStepOutcomeUnknownException(String safeReasonCode, Throwable cause) {
        this(safeReasonCode, cause, List.of());
    }

    public ReviewStepOutcomeUnknownException(String safeReasonCode, Throwable cause,
            List<ContractModelCallAudit> modelCalls) {
        super(cause);
        this.safeReasonCode = ReviewStepFailureTransactionCoordinator.safeReason(safeReasonCode);
        this.modelCalls = List.copyOf(modelCalls);
    }

    public String safeReasonCode() { return safeReasonCode; }
    public List<ContractModelCallAudit> modelCalls() { return modelCalls; }
}
