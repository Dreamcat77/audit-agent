package com.fuquan.audit.common.application.port;

/**
 * Append-only port for durable business-operation audit facts.
 * Implementations must not expose update or delete semantics.
 */
public interface OperationAuditPort {

    void append(OperationAuditEvent event);
}
