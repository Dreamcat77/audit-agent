package com.fuquan.audit.common.application.port;

import java.time.Instant;
import java.util.List;

public interface OperationAuditQueryPort {
    List<OperationAuditEvent> find(String userId, String action, String resourceType, Instant from, Instant to);
}
