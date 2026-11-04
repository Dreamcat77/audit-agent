package com.fuquan.audit.contract.application.project;

public record ProjectRequestMetadata(String requestId, String traceId, String sourceIp) {
    public ProjectRequestMetadata {
        requestId = safe(requestId);
        traceId = safe(traceId);
        sourceIp = sourceIp == null ? null : sourceIp.substring(0, Math.min(sourceIp.length(), 64));
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "unavailable" : value; }
}
