package com.fuquan.audit.contract.application;

/** Request/Trace/source IP 审计上下文，不作为业务主键。 */
public record ReviewRequestMetadata(String requestId, String traceId, String sourceIp) {
}
