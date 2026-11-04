package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import com.fuquan.audit.security.application.port.UserRoleAssignmentPort;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8UserAccountRepository implements UserAccountRepository, UserIdentityQueryPort, UserRoleAssignmentPort {

    private final UserAccountMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8UserAccountRepository(UserAccountMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String normalizedUsername) {
        long startedAt = System.nanoTime();
        UserAccountPersistenceRow row = mapper.findByUsername(normalizedUsername);
        UserAccount account = row == null ? null : toDomain(row);
        flowDebugLogger.log(
                "USER_REPOSITORY_READ",
                "AUTH",
                "REPOSITORY",
                "findByUsername",
                Map.of(
                        "repository", "UserAccountRepository",
                        "durationMs", elapsedMs(startedAt),
                        "outcome", row == null ? "NOT_FOUND" : "FOUND"));
        return Optional.ofNullable(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findDisplayNameByUserId(String userId) {
        return Optional.ofNullable(mapper.findById(userId)).map(UserAccountPersistenceRow::getRealName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findFirstActiveByRole(String roleCode) {
        return Optional.ofNullable(mapper.findFirstActiveByRole(roleCode)).map(this::toDomain)
                .filter(account -> account.status() == UserAccountStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findActiveById(String userId) {
        return Optional.ofNullable(mapper.findById(userId)).map(this::toDomain)
                .filter(account -> account.status() == UserAccountStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveRole(String roleCode) {
        return mapper.existsActiveRole(roleCode);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRole(String userId, String roleCode) {
        return mapper.existsUserRole(userId, roleCode);
    }

    @Override
    @Transactional
    public void assignRole(String userId, String roleCode) {
        int rowCount = mapper.insertUserRole(userId, roleCode);
        if (rowCount != 0 && rowCount != 1) {
            throw new IllegalStateException("user role assignment did not affect exactly one row");
        }
    }

    @Override @Transactional
    public void removeRole(String userId, String roleCode) { mapper.deleteUserRole(userId, roleCode); }

    @Override
    @Transactional(readOnly = true)
    public List<String> findActiveRoleCodesByUserId(String userId) {
        List<String> roles = mapper.findRoleCodesByUserId(userId);
        return roles == null ? List.of() : List.copyOf(roles);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount recordFailedLogin(
            String userId,
            int maxFailedAttempts,
            Instant lockUntil,
            Instant occurredAt) {
        int rowCount = mapper.incrementFailedLogin(
                userId,
                maxFailedAttempts,
                toUtc(lockUntil),
                toUtc(occurredAt));
        requireSingleRow(rowCount, "record failed login");
        return findRequiredById(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount recordSuccessfulLogin(String userId, Instant occurredAt) {
        int rowCount = mapper.recordSuccessfulLogin(userId, toUtc(occurredAt));
        requireSingleRow(rowCount, "record successful login");
        return findRequiredById(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount unlockIfExpired(String userId, Instant occurredAt) {
        mapper.unlockIfExpired(userId, toUtc(occurredAt));
        return findRequiredById(userId);
    }

    private UserAccount findRequiredById(String userId) {
        UserAccountPersistenceRow row = mapper.findById(userId);
        if (row == null) {
            throw new IllegalStateException("user account update target does not exist");
        }
        return toDomain(row);
    }

    private UserAccount toDomain(UserAccountPersistenceRow row) {
        List<String> roleCodes = mapper.findRoleCodesByUserId(row.getId());
        return new UserAccount(
                row.getId(),
                row.getUsername(),
                row.getPasswordHash(),
                row.getRealName(),
                UserAccountStatus.valueOf(row.getStatus()),
                row.getFailedLoginCount(),
                toInstant(row.getLockedUntil()),
                toInstant(row.getLastLoginAt()),
                toInstant(row.getPasswordChangedAt()),
                toInstant(row.getCreatedAt()),
                toInstant(row.getUpdatedAt()),
                row.getVersionNo(),
                Set.copyOf(roleCodes == null ? List.of() : roleCodes));
    }

    private static void requireSingleRow(int rowCount, String operation) {
        if (rowCount != 1) {
            throw new IllegalStateException(operation + " did not affect exactly one row");
        }
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "instant must not be null"), ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
