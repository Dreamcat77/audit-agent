package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8UserAccountRepositoryTest {

    @Mock
    private UserAccountMapper mapper;

    private Dm8UserAccountRepository repository;

    @BeforeEach
    void setUp() {
        repository = new Dm8UserAccountRepository(
                mapper,
                new FlowDebugLogger(new ObservabilityProperties()));
    }

    @Test
    void findsCanonicalUserAndMapsOnlyActiveRoleCodes() {
        UserAccountPersistenceRow row = row("ACTIVE", 0);
        when(mapper.findByUsername("auditor.one")).thenReturn(row);
        when(mapper.findRoleCodesByUserId(row.getId())).thenReturn(List.of("AUDITOR", "LEGAL"));

        var account = repository.findByUsername("auditor.one").orElseThrow();

        assertThat(account.username()).isEqualTo("auditor.one");
        assertThat(account.passwordHash()).startsWith("{bcrypt}");
        assertThat(account.status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(account.roles()).containsExactlyInAnyOrder("AUDITOR", "LEGAL");
    }

    @Test
    void failedLoginUsesAtomicMapperUpdateAndReturnsUpdatedCount() {
        UserAccountPersistenceRow updated = row("LOCKED", 5);
        updated.setLockedUntil(LocalDateTime.parse("2026-08-17T03:15:00"));
        when(mapper.incrementFailedLogin(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.findById(updated.getId())).thenReturn(updated);
        when(mapper.findRoleCodesByUserId(updated.getId())).thenReturn(List.of("AUDITOR"));
        Instant occurredAt = Instant.parse("2026-08-17T03:00:00Z");
        Instant lockUntil = Instant.parse("2026-08-17T03:15:00Z");

        var account = repository.recordFailedLogin(updated.getId(), 5, lockUntil, occurredAt);

        verify(mapper).incrementFailedLogin(
                updated.getId(),
                5,
                LocalDateTime.ofInstant(lockUntil, ZoneOffset.UTC),
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        assertThat(account.failedLoginCount()).isEqualTo(5);
        assertThat(account.status()).isEqualTo(UserAccountStatus.LOCKED);
    }

    @Test
    void successfulLoginUsesSingleAtomicUpdateAndReturnsResetAccount() {
        UserAccountPersistenceRow updated = row("ACTIVE", 0);
        updated.setLastLoginAt(LocalDateTime.parse("2026-08-17T04:00:00"));
        when(mapper.recordSuccessfulLogin(updated.getId(), updated.getLastLoginAt())).thenReturn(1);
        when(mapper.findById(updated.getId())).thenReturn(updated);
        when(mapper.findRoleCodesByUserId(updated.getId())).thenReturn(List.of("AUDITOR"));

        var account = repository.recordSuccessfulLogin(
                updated.getId(), updated.getLastLoginAt().toInstant(ZoneOffset.UTC));

        assertThat(account.failedLoginCount()).isZero();
        assertThat(account.lastLoginAt()).isEqualTo(Instant.parse("2026-08-17T04:00:00Z"));
    }

    @Test
    void resolvesCurrentDisplayNameFromExistingSysUserById() {
        UserAccountPersistenceRow row = row("ACTIVE", 0);
        row.setRealName("Current Display Name");
        when(mapper.findById(row.getId())).thenReturn(row);
        UserIdentityQueryPort identityQuery = repository;

        assertThat(identityQuery.findDisplayNameByUserId(row.getId())).contains("Current Display Name");
        verify(mapper).findById(row.getId());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void returnsEmptyWhenExistingSysUserByIdIsMissing() {
        when(mapper.findById("deleted-user")).thenReturn(null);
        UserIdentityQueryPort identityQuery = repository;

        assertThat(identityQuery.findDisplayNameByUserId("deleted-user")).isEmpty();
        verify(mapper).findById("deleted-user");
        verifyNoMoreInteractions(mapper);
    }

    private UserAccountPersistenceRow row(String status, int failedCount) {
        LocalDateTime now = LocalDateTime.parse("2026-08-17T01:00:00");
        UserAccountPersistenceRow row = new UserAccountPersistenceRow();
        row.setId("20000000-0000-0000-0000-000000000001");
        row.setUsername("auditor.one");
        row.setPasswordHash("{bcrypt}$2a$10$synthetic-unit-test-hash");
        row.setRealName("Audit User");
        row.setStatus(status);
        row.setFailedLoginCount(failedCount);
        row.setPasswordChangedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setVersionNo(failedCount);
        return row;
    }
}
