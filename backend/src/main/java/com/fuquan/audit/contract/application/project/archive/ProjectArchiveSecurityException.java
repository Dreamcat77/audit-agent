package com.fuquan.audit.contract.application.project.archive;

public final class ProjectArchiveSecurityException extends RuntimeException {
    private final String reasonCode;

    public ProjectArchiveSecurityException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public ProjectArchiveSecurityException(String reasonCode, Throwable cause) {
        super(reasonCode, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
