package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.security.application.port.LoginAuditEvent;
import com.fuquan.audit.security.domain.LoginAuditEventType;
import com.fuquan.audit.security.domain.LoginAuditResult;
import com.fuquan.audit.security.domain.LoginFailureReason;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8LoginAuditAdapterTest {

    @Mock
    private LoginAuditMapper mapper;

    @Test
    void insertsCorrelationAndFailureCodeWithoutCredentialFields() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        Dm8LoginAuditAdapter adapter = new Dm8LoginAuditAdapter(mapper);
        Instant occurredAt = Instant.parse("2026-08-17T02:00:00Z");

        adapter.append(new LoginAuditEvent(
                "user-1",
                "auditor",
                LoginAuditEventType.LOGIN_FAILED,
                LoginAuditResult.FAILURE,
                LoginFailureReason.BAD_CREDENTIALS,
                "request-1",
                "trace-1",
                "127.0.0.1",
                occurredAt));

        ArgumentCaptor<LoginAuditPersistenceRow> captor =
                ArgumentCaptor.forClass(LoginAuditPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        LoginAuditPersistenceRow row = captor.getValue();
        assertThat(row.failReason()).isEqualTo("BAD_CREDENTIALS");
        assertThat(row.requestId()).isEqualTo("request-1");
        assertThat(row.traceId()).isEqualTo("trace-1");
        assertThat(row.occurredAt().toInstant(ZoneOffset.UTC)).isEqualTo(occurredAt);

        assertThat(Arrays.stream(LoginAuditEvent.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .toList())
                .noneMatch(name -> name.contains("password")
                        || name.contains("session")
                        || name.contains("cookie")
                        || name.contains("csrf"));
    }
}
