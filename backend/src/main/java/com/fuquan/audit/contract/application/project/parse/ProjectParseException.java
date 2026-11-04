package com.fuquan.audit.contract.application.project.parse;

public final class ProjectParseException extends RuntimeException {
    private final String reasonCode;
    public ProjectParseException(String reasonCode) { super(reasonCode); this.reasonCode = reasonCode; }
    public ProjectParseException(String reasonCode, Throwable cause) { super(reasonCode, cause); this.reasonCode = reasonCode; }
    public String reasonCode() { return reasonCode; }
}
