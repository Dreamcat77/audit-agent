package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
@ConditionalOnProperty(
        name = "audit.operation-audit.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class Dm8OperationAuditAdapter implements OperationAuditPort, com.fuquan.audit.common.application.port.OperationAuditQueryPort {

    private final OperationAuditMapper mapper;
    private final Clock clock;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8OperationAuditAdapter(
            OperationAuditMapper mapper,
            Clock clock,
            FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.clock = clock;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OperationAuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        OperationAuditPersistenceRow row = new OperationAuditPersistenceRow(
                UUID.randomUUID().toString(),
                event.eventType(),
                event.module(),
                event.action(),
                event.userId(),
                event.username(),
                event.resourceType(),
                event.resourceId(),
                event.taskId(),
                event.resultStatus().name(),
                event.businessResult(),
                event.actionDesc(),
                event.detail(),
                event.requestId(),
                event.traceId(),
                event.sourceIp(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), event.resourceName(), event.beforeData(),
                event.afterData(), event.failureReason(), event.confirmationFieldCode(), event.confirmationFieldName(),
                event.confirmationFieldValue(), event.confirmationRulePrompt(), event.confirmationAiPrompt(),
                event.confirmationNote());
        int rowCount = mapper.insert(row);
        if (rowCount != 1) {
            throw new IllegalStateException("operation audit insert did not affect exactly one row");
        }
        flowDebugLogger.log(
                "OPERATION_AUDIT_INSERT",
                event.module(),
                "REPOSITORY",
                event.action());
    }
    @Override @Transactional(readOnly = true)
    public java.util.List<OperationAuditEvent> find(String userId, String action, String type, java.time.Instant from, java.time.Instant to) {
        return mapper.find(userId, action, type, local(from), local(to)).stream().map(r -> new OperationAuditEvent(
                r.eventType(), r.module(), r.action(), r.userId(), r.username(), r.resourceType(), r.resourceId(),
                r.taskId(), com.fuquan.audit.common.application.port.OperationAuditResultStatus.valueOf(r.resultStatus()),
                r.businessResult(), r.actionDesc(), r.detail(), r.requestId(), r.traceId(), r.sourceIp(),
                r.occurredAt().toInstant(ZoneOffset.UTC), r.resourceName(), r.beforeData(), r.afterData(), r.failureReason(),
                r.confirmationFieldCode(), r.confirmationFieldName(), r.confirmationFieldValue(),
                r.confirmationRulePrompt(), r.confirmationAiPrompt(), r.confirmationNote())).toList();
    }
    private static LocalDateTime local(java.time.Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
