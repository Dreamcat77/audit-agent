package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8OperationAuditAdapterTest {

    @Mock
    private OperationAuditMapper mapper;

    @Test
    void appendsOneStructuredRowWithoutUpdateOrDeletePath() {
        Instant occurredAt = Instant.parse("2026-08-17T01:02:03Z");
        Clock clock = Clock.fixed(Instant.parse("2026-08-17T01:03:00Z"), ZoneOffset.UTC);
        Dm8OperationAuditAdapter adapter = new Dm8OperationAuditAdapter(mapper, clock, flowLogger());
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        adapter.append(new OperationAuditEvent(
                "CONTRACT_REVIEW_COMPLETED",
                "CONTRACT",
                "REVIEW_COMPLETE",
                "user-1",
                "auditor",
                "CONTRACT",
                "contract-1",
                "task-1",
                OperationAuditResultStatus.SUCCESS,
                "PASS",
                "Contract review completed",
                "safe structured detail",
                "request-1",
                "0123456789abcdef0123456789abcdef",
                "127.0.0.1",
                occurredAt));

        ArgumentCaptor<OperationAuditPersistenceRow> captor =
                ArgumentCaptor.forClass(OperationAuditPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        OperationAuditPersistenceRow row = captor.getValue();
        assertThat(row.id()).hasSize(36);
        assertThat(row.eventType()).isEqualTo("CONTRACT_REVIEW_COMPLETED");
        assertThat(row.taskId()).isEqualTo("task-1");
        assertThat(row.resultStatus()).isEqualTo("SUCCESS");
        assertThat(row.occurredAt().toInstant(ZoneOffset.UTC)).isEqualTo(occurredAt);
        assertThat(row.createdAt().toInstant(ZoneOffset.UTC)).isEqualTo(clock.instant());
    }

    private FlowDebugLogger flowLogger() {
        return new FlowDebugLogger(new ObservabilityProperties());
    }
}
