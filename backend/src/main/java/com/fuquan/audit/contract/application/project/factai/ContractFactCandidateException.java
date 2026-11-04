package com.fuquan.audit.contract.application.project.factai;

public final class ContractFactCandidateException extends RuntimeException {
    public enum Reason { TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, BUSINESS_REFUSED, TOTAL_BUDGET_EXCEEDED, OUTCOME_UNKNOWN }

    private final Reason reason;

    public ContractFactCandidateException(Reason reason, Throwable cause) {
        super("contract fact candidate extraction failed safely", cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
