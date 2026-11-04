package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.security.application.port.LoginAuditEvent;
import com.fuquan.audit.security.application.port.LoginAuditPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8LoginAuditAdapter implements LoginAuditPort {

    private final LoginAuditMapper mapper;

    public Dm8LoginAuditAdapter(LoginAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LoginAuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        int rowCount = mapper.insert(new LoginAuditPersistenceRow(
                UUID.randomUUID().toString(),
                event.userId(),
                event.username(),
                event.eventType().name(),
                event.result().name(),
                event.failureReason() == null ? null : event.failureReason().name(),
                event.requestId(),
                event.traceId(),
                event.sourceIp(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)));
        if (rowCount != 1) {
            throw new IllegalStateException("login audit insert did not affect exactly one row");
        }
    }
}
