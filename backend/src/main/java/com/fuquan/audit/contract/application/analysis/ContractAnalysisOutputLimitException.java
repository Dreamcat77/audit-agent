package com.fuquan.audit.contract.application.analysis;

/** The provider proved that this response ended at its output limit. Not a timeout. */
public final class ContractAnalysisOutputLimitException extends RuntimeException {
    public ContractAnalysisOutputLimitException() { super("CONTRACT_ANALYSIS_OUTPUT_LIMIT"); }
}
