package com.fuquan.audit.contract.application.ai;

import java.util.Objects;
import java.util.List;

/** Safe, endpoint-local classification of a contract semantic analysis failure. */
public final class ContractTextAnalysisFailure extends RuntimeException {
    private final Reason reason;
    private final List<ContractTextAnalysisResult.ModelCall> modelCalls;

    public ContractTextAnalysisFailure(Reason reason) {
        this(reason, List.of());
    }

    public ContractTextAnalysisFailure(Reason reason,
            List<ContractTextAnalysisResult.ModelCall> modelCalls) {
        super("contract text analysis failed safely");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.modelCalls = List.copyOf(Objects.requireNonNull(modelCalls));
    }

    public List<ContractTextAnalysisResult.ModelCall> modelCalls() { return modelCalls; }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MODEL_INVALID_RESULT,
        MODEL_GATEWAY_ERROR,
        MODEL_TOTAL_BUDGET_EXHAUSTED,
        MODEL_NOT_CONFIGURED,
        MODEL_INPUT_TOO_LARGE,
        EMPTY_MODEL_INPUT,
        SERVICE_UNAVAILABLE
    }
}
