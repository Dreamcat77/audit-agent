package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8IdempotencyAdapterTest {

    @Mock
    private IdempotencyMapper mapper;

    @Test
    void firstClaimUsesInsertAsAtomicBoundary() {
        Dm8IdempotencyAdapter adapter = new Dm8IdempotencyAdapter(mapper);
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        var claim = adapter.claim(request());

        assertThat(claim.acquired()).isTrue();
        ArgumentCaptor<IdempotencyPersistenceRow> captor =
                ArgumentCaptor.forClass(IdempotencyPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("IN_PROGRESS");
        assertThat(captor.getValue().requestFingerprint()).isEqualTo("fingerprint-1");
    }

    @Test
    void uniqueConstraintConflictReadsExistingRecordInsteadOfLeakingDatabaseException() {
        Dm8IdempotencyAdapter adapter = new Dm8IdempotencyAdapter(mapper);
        when(mapper.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("synthetic unique conflict"));
        when(mapper.findByScopeAndKey("CONTRACT_F02_REVIEW", "key-1"))
                .thenReturn(row("IN_PROGRESS", 0));

        var claim = adapter.claim(request());

        assertThat(claim.acquired()).isFalse();
        assertThat(claim.record().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void dm8IntegrityViolationWithReadableScopeAndKeyIsDiscriminatedAsDuplicate() {
        Dm8IdempotencyAdapter adapter = new Dm8IdempotencyAdapter(mapper);
        when(mapper.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("synthetic DM8 integrity violation"));
        when(mapper.findByScopeAndKey("CONTRACT_F02_REVIEW", "key-1"))
                .thenReturn(row("FAILED", 1));

        var claim = adapter.claim(request());

        assertThat(claim.acquired()).isFalse();
        assertThat(claim.record().status()).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void dm8IntegrityViolationWithoutReadableScopeAndKeyPropagatesOriginalFailure() {
        Dm8IdempotencyAdapter adapter = new Dm8IdempotencyAdapter(mapper);
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("synthetic unrelated integrity violation");
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
        when(mapper.findByScopeAndKey("CONTRACT_F02_REVIEW", "key-1")).thenReturn(null);

        assertThatThrownBy(() -> adapter.claim(request())).isSameAs(failure);
        verify(mapper).findByScopeAndKey("CONTRACT_F02_REVIEW", "key-1");
    }

    @Test
    void failedRecordRetainsClaimedAuditTaskIdentity() {
        Dm8IdempotencyAdapter adapter = new Dm8IdempotencyAdapter(mapper);
        var claimed = row("IN_PROGRESS", 0);
        var failed = row("FAILED", 1);
        when(mapper.markFailed(
                claimed.id(), 0, LocalDateTime.ofInstant(now().plusSeconds(1), ZoneOffset.UTC)))
                .thenReturn(1);
        when(mapper.findById(claimed.id())).thenReturn(failed);

        var result = adapter.markFailed(
                new com.fuquan.audit.common.application.port.IdempotencyRecord(
                        java.util.UUID.fromString(claimed.id()), claimed.scope(), claimed.idempotencyKey(),
                        claimed.actorId(), claimed.resourceType(), claimed.resourceId(),
                        claimed.requestFingerprint(), IdempotencyStatus.IN_PROGRESS,
                        claimed.resultResourceType(), claimed.resultResourceId(), claimed.firstRequestId(),
                        claimed.traceId(), claimed.createdAt().toInstant(ZoneOffset.UTC), null, 0),
                now().plusSeconds(1));

        assertThat(result.status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(result.resultResourceType()).isEqualTo("AUDIT_TASK");
        assertThat(result.resultResourceId()).isEqualTo("task-1");
    }

    private IdempotencyRequest request() {
        return new IdempotencyRequest(
                "CONTRACT_F02_REVIEW", "key-1", "user-1", "CONTRACT", "contract-1",
                "fingerprint-1", "AUDIT_TASK", "task-1", "request-1", "trace-1", now());
    }

    private IdempotencyPersistenceRow row(String status, long version) {
        LocalDateTime now = LocalDateTime.ofInstant(now(), ZoneOffset.UTC);
        return new IdempotencyPersistenceRow(
                "30000000-0000-0000-0000-000000000001", "CONTRACT_F02_REVIEW", "key-1",
                "user-1", "CONTRACT", "contract-1", "fingerprint-1", status,
                "AUDIT_TASK", "task-1", "request-1", "trace-1", now, null, version);
    }

    private Instant now() {
        return Instant.parse("2026-08-17T00:00:00Z");
    }
}
