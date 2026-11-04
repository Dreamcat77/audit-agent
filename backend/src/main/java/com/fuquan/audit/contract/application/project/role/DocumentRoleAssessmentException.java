package com.fuquan.audit.contract.application.project.role;

public class DocumentRoleAssessmentException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Reason reason;

    public DocumentRoleAssessmentException(Reason reason, Throwable cause) {
        super("document role assessment failed safely", cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
