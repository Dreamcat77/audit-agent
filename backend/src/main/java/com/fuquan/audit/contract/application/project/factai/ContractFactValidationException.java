package com.fuquan.audit.contract.application.project.factai;

public final class ContractFactValidationException extends RuntimeException {
    private final ContractFactCandidateValidator.Reason reason;

    public ContractFactValidationException(ContractFactCandidateValidator.Reason reason) {
        super("contract fact candidate validation failed closed");
        this.reason = reason;
    }

    public ContractFactCandidateValidator.Reason getReason() {
        return reason;
    }
}
