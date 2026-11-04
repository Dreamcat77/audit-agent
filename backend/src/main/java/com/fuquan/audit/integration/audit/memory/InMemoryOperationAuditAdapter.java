package com.fuquan.audit.integration.audit.memory;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(
        name = "audit.operation-audit.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InMemoryOperationAuditAdapter implements OperationAuditPort, com.fuquan.audit.common.application.port.OperationAuditQueryPort {

    private final CopyOnWriteArrayList<OperationAuditEvent> events = new CopyOnWriteArrayList<>();
    private final FlowDebugLogger flowDebugLogger;

    public InMemoryOperationAuditAdapter(FlowDebugLogger flowDebugLogger) {
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public void append(OperationAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
        flowDebugLogger.log(
                "OPERATION_AUDIT_APPEND",
                event.module(),
                "ADAPTER",
                event.action());
    }

    public List<OperationAuditEvent> snapshot() {
        return List.copyOf(events);
    }
    public List<OperationAuditEvent> find(String userId, String action, String type, java.time.Instant from, java.time.Instant to) {
        return events.stream().filter(e -> userId == null || userId.isBlank() || userId.equals(e.userId()))
                .filter(e -> action == null || action.isBlank() || action.equals(e.action()))
                .filter(e -> type == null || type.isBlank() || type.equals(e.resourceType()))
                .filter(e -> from == null || !e.occurredAt().isBefore(from)).filter(e -> to == null || !e.occurredAt().isAfter(to))
                .sorted((a,b) -> b.occurredAt().compareTo(a.occurredAt())).toList();
    }
}
